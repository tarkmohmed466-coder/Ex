package com.example.renderer

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.example.arcore.ArCoreSessionManager
import com.example.arcore.ArCoreTrackingData
import com.example.model.DisplayMode
import com.google.ar.core.Anchor
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * High-performance Android SurfaceView bridging Google Filament and Google ARCore.
 * Supports Choreographer-driven 60+ FPS rendering, ARCore 6DoF camera synchronization,
 * Environmental HDR lighting, physical plane hit-testing & anchoring, and Dual-Viewport MR Stereo.
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

  // Active Anchors mapped to ARCore Anchor instances
  private val activeArAnchors = mutableListOf<Anchor>()

  // Gesture Detectors
  private val scaleGestureDetector: ScaleGestureDetector
  private var lastTouchX = 0f
  private var lastTouchY = 0f
  private var activePointerCount = 0
  private var touchStartTime = 0L

  // Telemetry callback
  var onTelemetryUpdate: ((fps: Float, drawCalls: Int, vertexCount: Int, trackingData: ArCoreTrackingData) -> Unit)? = null
  var onAnchorPlaced: ((Anchor, FloatArray) -> Unit)? = null

  // Latest AR tracking data
  private var latestTrackingData = ArCoreTrackingData()

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
    for (anchor in activeArAnchors) {
      anchor.detach()
    }
    activeArAnchors.clear()
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

    // Calculate FPS
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
          val projMatrix = FloatArray(16)
          val viewMatrix = FloatArray(16)
          frame.camera.getProjectionMatrix(projMatrix, 0, 0.05f, 50.0f)
          frame.camera.getViewMatrix(viewMatrix, 0)

          filamentEngine.setCameraFromArCore(projMatrix, viewMatrix)

          // Update active anchor poses
          val currentAsset = filamentEngine.currentAsset
          if (currentAsset != null && activeArAnchors.isNotEmpty()) {
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
        val headPoseMatrix = if (frame != null && frame.camera.trackingState == TrackingState.TRACKING) {
          val mat = FloatArray(16)
          frame.camera.getViewMatrix(mat, 0)
          mat
        } else {
          null
        }
        val ipdMeters = (filamentEngine.ambientIntensity / 1000f).coerceIn(0.055f, 0.075f) // fallback or default 64mm
        filamentEngine.renderStereoFrame(frameTimeNanos, 0.064f, headPoseMatrix)
      }
    }

    if (isRendering) {
      Choreographer.getInstance().postFrameCallback(this)
    }
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
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
        if (!scaleGestureDetector.isInProgress) {
          val dx = event.x - lastTouchX
          val dy = event.y - lastTouchY

          if (event.pointerCount == 1) {
            // Single finger orbit rotate
            if (displayMode == DisplayMode.OBJECT) {
              filamentEngine.orbitYaw += dx * 0.4f
              filamentEngine.orbitPitch = (filamentEngine.orbitPitch - dy * 0.4f).coerceIn(-85f, 85f)
            }
          } else if (event.pointerCount == 2) {
            // Two finger pan
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
    if (displayMode == DisplayMode.AR) {
      val frame = arCoreSessionManager.latestFrame ?: return
      val hit = arCoreSessionManager.hitTest(frame, xPx, yPx)
      if (hit != null) {
        val anchor = arCoreSessionManager.createAnchor(hit)
        if (anchor != null) {
          activeArAnchors.add(anchor)
          val hitPose = hit.hitPose
          val posArr = floatArrayOf(hitPose.tx(), hitPose.ty(), hitPose.tz())
          onAnchorPlaced?.invoke(anchor, posArr)
          Log.i(TAG, "ARCore Anchor pinned on plane at: ${hitPose.tx()}, ${hitPose.ty()}, ${hitPose.tz()}")
        }
      }
    }
  }

  fun loadGlbBuffer(buffer: ByteBuffer, title: String) {
    filamentEngine.loadAsset(buffer, title)
  }

  fun clearAnchors() {
    for (anchor in activeArAnchors) {
      anchor.detach()
    }
    activeArAnchors.clear()
  }

  fun resetView() {
    filamentEngine.resetTransforms()
    clearAnchors()
  }
}
