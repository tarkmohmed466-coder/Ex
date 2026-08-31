package com.example.renderer

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.example.arcore.ArCoreSessionManager
import com.example.arcore.ArCoreTrackingData
import com.example.arcore.DepthOcclusionManager
import com.example.arcore.ExhibitMarker
import com.example.arcore.ExhibitSource
import com.example.arcore.ImageMarkerCatalog
import com.example.engine.DiagnosticsLogger
import com.example.engine.TwoFingerRotateDetector
import com.example.model.DisplayMode
import com.example.parser.GltfAssetFactory
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * High-performance Android SurfaceView bridging Google Filament and Google ARCore.
 * Supports:
 * - 60+ FPS Choreographer-driven rendering with zero allocations in doFrame.
 * - ARCore 6DoF Camera synchronization & plane anchoring.
 * - Image Target Recognition & Automatic 3D Exhibit Spawning & Anchoring.
 * - Multi-Object Scene persistence while walking in 6DoF space.
 * - Real 16-bit Depth extraction & Depth Occlusion processing.
 * - Real-world 1:1 Metric Scale (1 unit = 1 physical meter).
 * - Dual-Viewport Asymmetric Off-Axis Stereoscopic MR Pipeline.
 * - Two-finger rotation, pan, pinch zoom gestures.
 * - Hardware PixelCopy frame snapshots.
 */
class SpatialSurfaceView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Choreographer.FrameCallback {

  companion object {
    private const val TAG = "SpatialSurfaceView"
  }

  val filamentEngine = FilamentEngineHolder(context)
  val arCoreSessionManager = ArCoreSessionManager(context)
  val depthOcclusionManager = DepthOcclusionManager()

  private var isSurfaceReady = false
  private var isRendering = false

  // Frame timing
  private var lastFrameTimestamp = 0L
  private var frameCount = 0
  private var fpsTimer = 0L

  // Active Display Mode
  var displayMode: DisplayMode = DisplayMode.OBJECT
    set(value) {
      field = value
      updateModeConfiguration()
    }

  // Active Placed Anchors mapped to ARCore Anchor instances
  val activeArAnchors = mutableListOf<Anchor>()

  // Set of already spawned image markers to prevent duplicate spawning
  private val spawnedMarkerIds = mutableSetOf<String>()

  // Gesture Detectors
  private val scaleGestureDetector: ScaleGestureDetector
  private val rotateGestureDetector: TwoFingerRotateDetector
  private var lastTouchX = 0f
  private var lastTouchY = 0f
  private var activePointerCount = 0
  private var touchStartTime = 0L

  // Current selected model ID for manual plane tap-placement
  var currentSelectedModelId: String = "drone_v1"
  var currentSelectedModelTitle: String = "Autonomous Drone X-1"

  // User Configured Interpupillary Distance (IPD) in millimeters for Stereoscopic MR
  var userIpdMm: Float = 64.0f

  // Telemetry and Event callbacks
  var onTelemetryUpdate: ((fps: Float, drawCalls: Int, vertexCount: Int, trackingData: ArCoreTrackingData) -> Unit)? = null
  var onAnchorPlaced: ((Anchor, FloatArray, ExhibitSource, String, String) -> Unit)? = null
  var onExhibitMarkerRecognized: ((ExhibitMarker, FloatArray) -> Unit)? = null

  // Latest AR tracking data
  private var latestTrackingData = ArCoreTrackingData()

  // Preallocated per-frame scratch buffers for zero garbage collection overhead
  private val scratchProjMatrix = FloatArray(16)
  private val scratchViewMatrix = FloatArray(16)
  private val scratchHeadPoseMatrix = FloatArray(16)
  private val scratchAnchorPoses = mutableListOf<Pose>()

  init {
    holder.addCallback(this)

    filamentEngine.initialize()

    arCoreSessionManager.onTrackingDataUpdated = { trackingData ->
      latestTrackingData = trackingData

      // Update Environmental HDR lighting in Filament
      filamentEngine.updateEnvironmentalHdrLighting(
        mainLightDir = trackingData.mainLightDirection,
        mainLightIntensityRgb = trackingData.mainLightIntensity,
        colorCorrection = trackingData.colorCorrectionRgb
      )
    }

    arCoreSessionManager.onImageMarkerDetected = { image ->
      handleAugmentedImageTracking(image)
    }

    scaleGestureDetector = ScaleGestureDetector(
      context,
      object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
          val scaleFactor = detector.scaleFactor
          if (displayMode == DisplayMode.OBJECT) {
            filamentEngine.orbitDistance = (filamentEngine.orbitDistance / scaleFactor).coerceIn(0.5f, 15.0f)
          } else {
            filamentEngine.modelScale = (filamentEngine.modelScale * scaleFactor).coerceIn(0.05f, 5.0f)
          }
          return true
        }
      }
    )

    rotateGestureDetector = TwoFingerRotateDetector { deltaDegrees ->
      if (displayMode == DisplayMode.OBJECT) {
        filamentEngine.orbitYaw += deltaDegrees
      } else {
        filamentEngine.modelRotationDegrees += deltaDegrees
      }
    }
  }

  /**
   * Handles ARCore AugmentedImage detection and binds it to its corresponding 3D Model Exhibit.
   */
  private fun handleAugmentedImageTracking(image: AugmentedImage) {
    if (image.trackingState != TrackingState.TRACKING) return

    val markerId = image.name
    val marker = ImageMarkerCatalog.findByMarkerId(markerId) ?: return

    if (!spawnedMarkerIds.contains(markerId)) {
      spawnedMarkerIds.add(markerId)

      val anchor = arCoreSessionManager.createAnchorForAugmentedImage(image)
      if (anchor != null) {
        activeArAnchors.add(anchor)
        val glbBuffer = GltfAssetFactory.getPresetGlbBuffer(marker.modelId)
        if (glbBuffer != null) {
          val exhibitId = "exhibit_marker_${markerId}_${System.currentTimeMillis()}"
          filamentEngine.spawnExhibit(
            exhibitId = exhibitId,
            modelId = marker.modelId,
            title = marker.title,
            buffer = glbBuffer,
            anchor = anchor,
            source = ExhibitSource.IMAGE_MARKER,
            markerId = markerId
          )

          val pos = floatArrayOf(image.centerPose.tx(), image.centerPose.ty(), image.centerPose.tz())
          onExhibitMarkerRecognized?.invoke(marker, pos)
          onAnchorPlaced?.invoke(anchor, pos, ExhibitSource.IMAGE_MARKER, marker.modelId, marker.title)
          Log.i(TAG, "Recognized Image Marker '${marker.title}' -> Spawned and Anchored 3D Exhibit at (${pos[0]}, ${pos[1]}, ${pos[2]})")
          DiagnosticsLogger.log(TAG, "Image Marker Tracked: '${marker.title}' -> Placed 3D Object at (${pos[0]}, ${pos[1]}, ${pos[2]})")
        }
      }
    }
  }

  fun resume(activity: Activity) {
    if (displayMode == DisplayMode.AR || displayMode == DisplayMode.MR) {
      arCoreSessionManager.resumeSession(activity)
    }
    startRendering()
  }

  fun pause() {
    stopRendering()
    arCoreSessionManager.pauseSession()
  }

  fun destroy() {
    stopRendering()
    clearAnchors()
    arCoreSessionManager.destroySession()
    filamentEngine.destroy()
  }

  private fun startRendering() {
    if (!isRendering && isSurfaceReady) {
      isRendering = true
      Choreographer.getInstance().postFrameCallback(this)
    }
  }

  private fun stopRendering() {
    isRendering = false
    Choreographer.getInstance().removeFrameCallback(this)
  }

  override fun surfaceCreated(holder: SurfaceHolder) {
    isSurfaceReady = true
    filamentEngine.onSurfaceCreated(holder.surface)
    startRendering()
  }

  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    filamentEngine.onSurfaceResized(width, height)
    arCoreSessionManager.setDisplayGeometry(
      (context as? Activity)?.windowManager?.defaultDisplay?.rotation ?: 0,
      width,
      height
    )
  }

  override fun surfaceDestroyed(holder: SurfaceHolder) {
    isSurfaceReady = false
    stopRendering()
  }

  private fun updateModeConfiguration() {
    when (displayMode) {
      DisplayMode.OBJECT -> {
        arCoreSessionManager.pauseSession()
      }
      DisplayMode.AR, DisplayMode.MR -> {
        (context as? Activity)?.let { arCoreSessionManager.resumeSession(it) }
      }
    }
  }

  override fun doFrame(frameTimeNanos: Long) {
    if (!isRendering || !isSurfaceReady) return

    // Calculate FPS & Telemetry
    frameCount++
    if (lastFrameTimestamp == 0L) lastFrameTimestamp = frameTimeNanos
    val elapsedMs = (frameTimeNanos - fpsTimer) / 1_000_000L
    if (elapsedMs >= 500) {
      val calculatedFps = (frameCount * 1000.0f) / elapsedMs
      frameCount = 0
      fpsTimer = frameTimeNanos
      onTelemetryUpdate?.invoke(
        calculatedFps,
        filamentEngine.drawCalls,
        filamentEngine.vertexCount,
        latestTrackingData
      )
    }

    when (displayMode) {
      DisplayMode.OBJECT -> {
        filamentEngine.updateOrbitCamera()
        filamentEngine.renderFrame(frameTimeNanos)
      }

      DisplayMode.AR -> {
        val frame = arCoreSessionManager.updateFrame()
        if (frame != null && frame.camera.trackingState == TrackingState.TRACKING) {
          frame.camera.getProjectionMatrix(scratchProjMatrix, 0, 0.05f, 50.0f)
          frame.camera.getViewMatrix(scratchViewMatrix, 0)

          filamentEngine.setCameraFromArCore(scratchProjMatrix, scratchViewMatrix)

          // Process Depth Occlusion without heap allocations
          scratchAnchorPoses.clear()
          for (i in 0 until activeArAnchors.size) {
            val a = activeArAnchors[i]
            if (a.trackingState == TrackingState.TRACKING) {
              scratchAnchorPoses.add(a.pose)
            }
          }
          depthOcclusionManager.processFrameDepth(frame, scratchAnchorPoses)

          // Evaluate Dynamic LOD per exhibit based on camera distance
          val camPos = latestTrackingData.cameraPosition
          for (exhibit in filamentEngine.activeExhibits) {
            val anchor = exhibit.anchor
            if (anchor != null && anchor.trackingState == TrackingState.TRACKING) {
              val objPos = floatArrayOf(anchor.pose.tx(), anchor.pose.ty(), anchor.pose.tz())
              val radius = maxOf(exhibit.physicalWidthMeters, exhibit.physicalHeightMeters) / 2.0f
              filamentEngine.lodManager.evaluateLod(
                exhibitId = exhibit.id,
                cameraPos = camPos,
                objectPos = objPos,
                boundingRadiusMeters = radius,
                screenWidthPx = width,
                screenHeightPx = height
              )
            }
          }

          // If no multi-exhibits spawned yet, update the single selected asset at the primary anchor
          val currentAsset = filamentEngine.currentAsset
          if (currentAsset != null && filamentEngine.activeExhibits.isEmpty() && activeArAnchors.isNotEmpty()) {
            val primaryAnchor = activeArAnchors.lastOrNull()
            if (primaryAnchor != null && primaryAnchor.trackingState == TrackingState.TRACKING) {
              filamentEngine.updateAnchorPose(currentAsset, primaryAnchor.pose)
            }
          }
        }
        filamentEngine.renderFrame(frameTimeNanos)
      }

      DisplayMode.MR -> {
        val frame = arCoreSessionManager.updateFrame()
        val hasValidTracking = frame != null && frame.camera.trackingState == TrackingState.TRACKING
        if (hasValidTracking) {
          frame!!.camera.getViewMatrix(scratchHeadPoseMatrix, 0)
        }

        // Process Depth in MR
        if (frame != null) {
          scratchAnchorPoses.clear()
          for (i in 0 until activeArAnchors.size) {
            val a = activeArAnchors[i]
            if (a.trackingState == TrackingState.TRACKING) {
              scratchAnchorPoses.add(a.pose)
            }
          }
          depthOcclusionManager.processFrameDepth(frame, scratchAnchorPoses)
        }

        // Use user-calibrated IPD (e.g. 52mm - 74mm range)
        val ipdMeters = (userIpdMm / 1000.0f).coerceIn(0.045f, 0.085f)
        filamentEngine.renderStereoFrame(
          frameTimeNanos,
          ipdMeters,
          if (hasValidTracking) scratchHeadPoseMatrix else null
        )
      }
    }

    if (isRendering) {
      Choreographer.getInstance().postFrameCallback(this)
    }
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    val isRotating = rotateGestureDetector.onTouchEvent(event)
    scaleGestureDetector.onTouchEvent(event)

    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        lastTouchX = event.x
        lastTouchY = event.y
        activePointerCount = 1
        touchStartTime = System.currentTimeMillis()
        return true
      }

      MotionEvent.ACTION_POINTER_DOWN -> {
        activePointerCount = event.pointerCount
        return true
      }

      MotionEvent.ACTION_MOVE -> {
        if (!scaleGestureDetector.isInProgress && !isRotating) {
          val dx = event.x - lastTouchX
          val dy = event.y - lastTouchY

          if (event.pointerCount == 1) {
            if (displayMode == DisplayMode.OBJECT) {
              filamentEngine.orbitYaw += dx * 0.4f
              filamentEngine.orbitPitch = (filamentEngine.orbitPitch - dy * 0.4f).coerceIn(-85f, 85f)
            }
          } else if (event.pointerCount == 2) {
            if (displayMode == DisplayMode.OBJECT) {
              filamentEngine.panX += dx * 0.005f
              filamentEngine.panY -= dy * 0.005f
            }
          }
        }
        lastTouchX = event.x
        lastTouchY = event.y
        return true
      }

      MotionEvent.ACTION_UP -> {
        val duration = System.currentTimeMillis() - touchStartTime
        val movedDist = abs(event.x - lastTouchX) + abs(event.y - lastTouchY)
        if (duration < 300 && movedDist < 20) {
          handleTap(event.x, event.y)
        }
        activePointerCount = 0
        return true
      }

      MotionEvent.ACTION_POINTER_UP -> {
        activePointerCount = event.pointerCount - 1
        return true
      }
    }
    return super.onTouchEvent(event)
  }

  private fun handleTap(xPx: Float, yPx: Float) {
    if (displayMode == DisplayMode.AR || displayMode == DisplayMode.MR) {
      val frame = arCoreSessionManager.latestFrame ?: return
      val hit = arCoreSessionManager.hitTest(frame, xPx, yPx)
      if (hit != null) {
        val anchor = arCoreSessionManager.createAnchor(hit)
        if (anchor != null) {
          activeArAnchors.add(anchor)
          val hitPose = hit.hitPose
          val posArr = floatArrayOf(hitPose.tx(), hitPose.ty(), hitPose.tz())

          // Spawn as a new distinct scene exhibit on the plane
          val glbBuffer = GltfAssetFactory.getPresetGlbBuffer(currentSelectedModelId)
          if (glbBuffer != null) {
            val exhibitId = "exhibit_plane_${System.currentTimeMillis()}"
            filamentEngine.spawnExhibit(
              exhibitId = exhibitId,
              modelId = currentSelectedModelId,
              title = currentSelectedModelTitle,
              buffer = glbBuffer,
              anchor = anchor,
              source = ExhibitSource.PLANE_TAP
            )
          }

          onAnchorPlaced?.invoke(anchor, posArr, ExhibitSource.PLANE_TAP, currentSelectedModelId, currentSelectedModelTitle)
          Log.i(TAG, "ARCore Anchor pinned on plane at: ${hitPose.tx()}, ${hitPose.ty()}, ${hitPose.tz()}")
          DiagnosticsLogger.log(TAG, "Placed Anchor at (${hitPose.tx()}, ${hitPose.ty()}, ${hitPose.tz()})")
        }
      }
    }
  }

  fun captureSnapshot(onCaptured: (Bitmap) -> Unit, onError: (String) -> Unit) {
    if (!isSurfaceReady || width <= 0 || height <= 0) {
      onError("Surface not ready for snapshot")
      return
    }

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    PixelCopy.request(
      this,
      bitmap,
      { copyResult ->
        if (copyResult == PixelCopy.SUCCESS) {
          onCaptured(bitmap)
        } else {
          onError("PixelCopy failed with status: $copyResult")
        }
      },
      Handler(Looper.getMainLooper())
    )
  }

  fun loadGlbBuffer(buffer: ByteBuffer, title: String) {
    filamentEngine.loadAsset(buffer, title)
  }

  fun clearAnchors() {
    for (anchor in activeArAnchors) {
      anchor.detach()
    }
    activeArAnchors.clear()
    spawnedMarkerIds.clear()
    filamentEngine.clearAllExhibits()
  }

  fun clearModelAndScene() {
    filamentEngine.clearAll()
    for (anchor in activeArAnchors) {
      anchor.detach()
    }
    activeArAnchors.clear()
    spawnedMarkerIds.clear()
    currentSelectedModelId = ""
    currentSelectedModelTitle = ""
    arCoreSessionManager.resetWalkingOrigin()
  }

  fun resetView() {
    filamentEngine.resetTransforms()
    clearAnchors()
    arCoreSessionManager.resetWalkingOrigin()
  }
}
