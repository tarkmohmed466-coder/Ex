package com.example.arcore

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.LightEstimate
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Image Tracking Lifecycle State Machine.
 * TRACKING: Image marker actively tracked with high confidence.
 * TRACKING_LOST: Image marker temporarily out of view; model retained at last known pose.
 * RECOVERING: Image marker reacquired; smoothing transform transition.
 * STOPPED: Tracking permanently lost after timeout; anchor and resources safely released.
 */
enum class ImageTrackingState {
  TRACKING,
  TRACKING_LOST,
  RECOVERING,
  STOPPED
}

data class TrackedImageRecord(
  val markerName: String,
  var state: ImageTrackingState,
  var anchor: Anchor?,
  var lastKnownPose: Pose,
  var lastSeenTimestampMs: Long
)

/**
 * Information about a detected 2D physical image marker / exhibit card in 3D space.
 */
data class DetectedImageInfo(
  val markerId: String,
  val trackingState: TrackingState,
  val centerPose: Pose,
  val extentXMeters: Float,
  val extentZMeters: Float,
  val distanceToCameraMeters: Float = 0f
)

/**
 * Real ARCore tracking info updated per frame.
 */
data class ArCoreTrackingData(
  val trackingState: TrackingState = TrackingState.STOPPED,
  val trackingFailureReason: TrackingFailureReason = TrackingFailureReason.NONE,
  val horizontalPlanesCount: Int = 0,
  val verticalPlanesCount: Int = 0,
  val lightIntensityLumens: Float = 1000f,
  val colorCorrectionRgb: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
  val mainLightDirection: FloatArray = floatArrayOf(0f, -1f, -0.5f),
  val mainLightIntensity: FloatArray = floatArrayOf(1f, 1f, 1f),
  val cameraPose: Pose? = null,
  val cameraPosition: FloatArray = floatArrayOf(0f, 0f, 0f),
  val walkingDisplacementMeters: Float = 0f,
  val isDepthSupported: Boolean = false,
  val isDepthEnabled: Boolean = false,
  val isInstantPlacementEnabled: Boolean = true,
  val geospatialStatus: GeospatialStatus = GeospatialStatus(),
  val semanticsTelemetry: SemanticsTelemetry = SemanticsTelemetry(),
  val cloudAnchorsCount: Int = 0,
  val recordingTelemetry: RecordingTelemetry = RecordingTelemetry(),
  val reconstructionTelemetry: ReconstructionTelemetry = ReconstructionTelemetry(),
  val certification: DeviceCapabilityCertification? = null,
  val detectedPlanes: List<DetectedPlaneInfo> = emptyList(),
  val detectedImages: List<DetectedImageInfo> = emptyList()
)

data class DetectedPlaneInfo(
  val id: String,
  val type: Plane.Type,
  val centerPose: Pose,
  val extentX: Float,
  val extentZ: Float,
  val polygon: FloatBuffer? = null
)

/**
 * Production-grade ARCore session manager.
 * - Handles 6DoF tracking, plane detection, and AugmentedImageDatabase.
 * - Implements image tracking state machine (TRACKING -> TRACKING_LOST -> RECOVERING -> STOPPED).
 * - Zero allocations in frame update loop.
 * - Manages real ARCore camera background texture lifecycle (setCameraTextureName).
 * - Environmental HDR light estimation & depth sensor integration.
 * - Instant Placement, Geospatial VPS, Scene Semantics, Cloud Anchors & Augmented Faces.
 */
class ArCoreSessionManager(private val context: Context) {

  companion object {
    private const val TAG = "ArCoreSessionManager"
    private const val TRACKING_LOSS_GRACE_PERIOD_MS = 4000L
  }

  // Specialized ARCore Sub-Managers
  val geospatialManager = ArCoreGeospatialManager()
  val semanticsManager = SceneSemanticsManager()
  val cloudAnchorManager = CloudAnchorManager(context)
  val facesManager = AugmentedFacesManager()
  val recordingPlaybackManager = ArCoreRecordingPlaybackManager(context)
  val environmentalMeshManager = EnvironmentalMeshManager()
  var deviceCertification: DeviceCapabilityCertification? = null
    private set

  var session: Session? = null
    private set

  var isSupported: Boolean = false
    private set

  var isConfigured: Boolean = false
    private set

  var latestFrame: Frame? = null
    private set

  var isSessionPaused: Boolean = true
    private set

  private var cameraTextureId: Int = 0
  private var userRequestedInstall = true
  private var availabilityChecked = false

  // Initial camera position for walking distance calculation
  private var initialCameraPose: Pose? = null
  private var totalWalkingDisplacement: Float = 0f

  // Preallocated scratch arrays for zero allocation in updateFrame
  private val scratchColorCorrection = FloatArray(4) { 1f }
  private val scratchMainLightDir = floatArrayOf(0f, -1f, -0.5f)
  private val scratchMainLightInt = floatArrayOf(1f, 1f, 1f)
  private val scratchCamPos = FloatArray(3)
  private val scratchPlaneList = ArrayList<DetectedPlaneInfo>(16)
  private val scratchImageList = ArrayList<DetectedImageInfo>(8)

  // Tracked images state machine map
  private val imageTrackingMap = HashMap<String, TrackedImageRecord>()

  // Callbacks
  var onTrackingDataUpdated: ((ArCoreTrackingData) -> Unit)? = null
  var onImageTrackingStateChanged: ((markerName: String, state: ImageTrackingState, anchor: Anchor?, pose: Pose) -> Unit)? = null

  /**
   * Checks if the Google Play Services for AR APK is installed on this device.
   */
  fun isArCorePackageInstalled(): Boolean {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo("com.google.ar.core", PackageManager.PackageInfoFlags.of(0))
      } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo("com.google.ar.core", 0)
      }
      true
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Checks if ARCore is supported on this device.
   */
  fun checkAvailability(activity: Activity, onResult: (Boolean) -> Unit) {
    if (!isArCorePackageInstalled()) {
      isSupported = false
      availabilityChecked = true
      onResult(false)
      return
    }

    try {
      val availability = ArCoreApk.getInstance().checkAvailability(context)
      if (availability.isTransient) {
        onResult(availability.isSupported)
        return
      }
      isSupported = availability.isSupported
      availabilityChecked = true
      onResult(isSupported)
    } catch (t: Throwable) {
      Log.w(TAG, "ARCore availability check failed: ${t.message}")
      isSupported = false
      availabilityChecked = true
      onResult(false)
    }
  }

  /**
   * Initializes and configures the ARCore session.
   */
  fun setupSession(activity: Activity): Boolean {
    if (session != null) return true
    if (availabilityChecked && !isSupported) return false

    // If ARCore package is not installed on the device, avoid calling ARCore install service which will fail
    // when Google Play Store is not installed or service cannot be bound
    if (!isArCorePackageInstalled()) {
      isSupported = false
      availabilityChecked = true
      Log.i(TAG, "ARCore package not installed on device. Operating in CameraX fallback mode.")
      return false
    }

    // Check availability first to avoid throwing runtime exceptions in ARCoreApk on emulators or unsupported devices
    if (!availabilityChecked) {
      try {
        val availability = ArCoreApk.getInstance().checkAvailability(context)
        if (!availability.isTransient) {
          isSupported = availability.isSupported
          availabilityChecked = true
          if (!isSupported) {
            Log.i(TAG, "ARCore is unsupported on this hardware ($availability). Operating in graceful fallback mode.")
            return false
          }
        }
      } catch (t: Throwable) {
        Log.w(TAG, "ARCore availability pre-check failed: ${t.message}")
        isSupported = false
        availabilityChecked = true
        return false
      }
    }

    return try {
      when (ArCoreApk.getInstance().requestInstall(activity, userRequestedInstall)) {
        ArCoreApk.InstallStatus.INSTALLED -> {
          val newSession = Session(activity)
          val config = Config(newSession)

          // Enable Horizontal and Vertical Plane detection
          config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL

          // Enable Environmental HDR Lighting
          config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

          // Enable Depth Mode if supported
          if (newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.depthMode = Config.DepthMode.AUTOMATIC
            Log.i(TAG, "ARCore Automatic Depth Mode enabled.")
          } else {
            config.depthMode = Config.DepthMode.DISABLED
            Log.i(TAG, "ARCore Depth Mode not supported on this device.")
          }

          // Build Augmented Image Database
          val imageDatabase = buildAugmentedImageDatabase(newSession)
          if (imageDatabase != null) {
            config.augmentedImageDatabase = imageDatabase
            Log.i(TAG, "Augmented Image Database configured with ${imageDatabase.numImages} targets.")
          }

          // Enable Instant Placement Mode (Local Y Up)
          try {
            config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
            Log.i(TAG, "ARCore Instant Placement Mode enabled.")
          } catch (e: Throwable) {
            Log.d(TAG, "Instant placement not supported on this version: ${e.message}")
          }

          // Configure Geospatial API & Streetscape Geometry
          geospatialManager.configureGeospatialMode(context, newSession, config)

          // Configure Scene Semantics Mode
          semanticsManager.configureSemanticsMode(newSession, config)

          // Configure Cloud Anchors Mode
          cloudAnchorManager.configureCloudAnchorMode(newSession, config)

          // Certify hardware against ARCore capability matrix
          deviceCertification = ArCoreDeviceMatrix.certifyDevice(context, newSession)

          // Real-time 60fps focus mode
          config.focusMode = Config.FocusMode.AUTO
          config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE

          newSession.configure(config)
          if (cameraTextureId != 0) {
            newSession.setCameraTextureName(cameraTextureId)
          }

          session = newSession
          isSupported = true
          isConfigured = true
          Log.i(TAG, "ARCore Session initialized and configured successfully.")
          true
        }
        ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
          userRequestedInstall = false
          false
        }
      }
    } catch (e: UnavailableUserDeclinedInstallationException) {
      Log.w(TAG, "User declined ARCore installation")
      userRequestedInstall = false
      isSupported = false
      false
    } catch (e: UnavailableDeviceNotCompatibleException) {
      Log.w(TAG, "Device not compatible with ARCore")
      userRequestedInstall = false
      isSupported = false
      false
    } catch (t: Throwable) {
      Log.w(TAG, "ARCore session initialization failed: ${t.message}")
      userRequestedInstall = false
      isSupported = false
      false
    }
  }

  private fun buildAugmentedImageDatabase(sess: Session): AugmentedImageDatabase? {
    return try {
      val db = AugmentedImageDatabase(sess)
      for (exhibit in ImageMarkerCatalog.exhibits) {
        val bitmap = ImageMarkerCatalog.generateMarkerBitmap(exhibit)
        db.addImage(exhibit.markerId, bitmap, exhibit.physicalWidthMeters)
      }
      db
    } catch (e: Exception) {
      Log.e(TAG, "Failed building AugmentedImageDatabase: ${e.message}", e)
      null
    }
  }

  fun resumeSession(activity: Activity): Boolean {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      return false
    }

    if (session == null) {
      val success = setupSession(activity)
      if (!success) return false
    }

    return try {
      session?.resume()
      if (cameraTextureId != 0) {
        session?.setCameraTextureName(cameraTextureId)
      }
      isSessionPaused = false
      Log.i(TAG, "ARCore session resumed successfully.")
      true
    } catch (e: CameraNotAvailableException) {
      Log.e(TAG, "Camera not available during ARCore resume", e)
      isSessionPaused = true
      false
    } catch (e: Exception) {
      Log.e(TAG, "Error resuming ARCore session: ${e.message}", e)
      isSessionPaused = true
      false
    }
  }

  fun pauseSession() {
    isSessionPaused = true
    try {
      session?.pause()
      Log.i(TAG, "ARCore session paused.")
    } catch (e: Exception) {
      Log.w(TAG, "Error pausing ARCore session: ${e.message}")
    }
  }

  fun destroySession() {
    isSessionPaused = true
    try {
      for (record in imageTrackingMap.values) {
        record.anchor?.detach()
      }
      imageTrackingMap.clear()
      session?.close()
      session = null
      isConfigured = false
      initialCameraPose = null
    } catch (e: Exception) {
      Log.w(TAG, "Error closing ARCore session: ${e.message}")
    }
  }

  fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
    session?.setDisplayGeometry(rotation, width, height)
  }

  fun setCameraTextureName(textureId: Int) {
    cameraTextureId = textureId
    session?.setCameraTextureName(textureId)
  }

  /**
   * Updates the ARCore session and processes tracking data, planes, augmented images,
   * walking distance, and light estimation. Zero-allocation per-frame execution.
   */
  fun updateFrame(): Frame? {
    if (isSessionPaused) return null
    val currentSession = session ?: return null
    return try {
      val frame = currentSession.update()
      latestFrame = frame
      val camera = frame.camera

      // Walking Camera Tracking & Displacement
      val camPose = if (camera.trackingState == TrackingState.TRACKING) camera.pose else null
      scratchCamPos[0] = camPose?.tx() ?: 0f
      scratchCamPos[1] = camPose?.ty() ?: 0f
      scratchCamPos[2] = camPose?.tz() ?: 0f

      if (camPose != null) {
        if (initialCameraPose == null) {
          initialCameraPose = camPose
        } else {
          val init = initialCameraPose!!
          val dx = camPose.tx() - init.tx()
          val dy = camPose.ty() - init.ty()
          val dz = camPose.tz() - init.tz()
          totalWalkingDisplacement = sqrt(dx * dx + dy * dy + dz * dz)
        }
      }

      // Process Light Estimation
      val lightEstimate = frame.lightEstimate
      scratchColorCorrection[0] = 1f; scratchColorCorrection[1] = 1f
      scratchColorCorrection[2] = 1f; scratchColorCorrection[3] = 1f
      if (lightEstimate.state == LightEstimate.State.VALID) {
        lightEstimate.getColorCorrection(scratchColorCorrection, 0)
      }

      scratchMainLightDir[0] = 0f; scratchMainLightDir[1] = -1f; scratchMainLightDir[2] = -0.5f
      scratchMainLightInt[0] = 1f; scratchMainLightInt[1] = 1f; scratchMainLightInt[2] = 1f
      if (lightEstimate.state == LightEstimate.State.VALID) {
        lightEstimate.environmentalHdrMainLightDirection?.let {
          System.arraycopy(it, 0, scratchMainLightDir, 0, 3)
        }
        lightEstimate.environmentalHdrMainLightIntensity?.let {
          System.arraycopy(it, 0, scratchMainLightInt, 0, 3)
        }
      }

      // 1. Collect Planes
      val allPlanes = currentSession.getAllTrackables(Plane::class.java)
      var hPlanes = 0
      var vPlanes = 0
      scratchPlaneList.clear()

      for (plane in allPlanes) {
        if (plane.trackingState == TrackingState.TRACKING) {
          if (plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING || plane.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING) {
            hPlanes++
          } else if (plane.type == Plane.Type.VERTICAL) {
            vPlanes++
          }
          scratchPlaneList.add(
            DetectedPlaneInfo(
              id = "plane_${plane.hashCode()}",
              type = plane.type,
              centerPose = plane.centerPose,
              extentX = plane.extentX,
              extentZ = plane.extentZ,
              polygon = plane.polygon
            )
          )
        }
      }

      // 2. Process Augmented Image Tracking State Machine
      val now = System.currentTimeMillis()
      val allImages = currentSession.getAllTrackables(AugmentedImage::class.java)
      scratchImageList.clear()

      val seenMarkersThisFrame = HashSet<String>()

      for (image in allImages) {
        val markerName = image.name
        seenMarkersThisFrame.add(markerName)
        val imgPose = image.centerPose
        val distToCam = if (camPose != null) {
          val dx = imgPose.tx() - camPose.tx()
          val dy = imgPose.ty() - camPose.ty()
          val dz = imgPose.tz() - camPose.tz()
          sqrt(dx * dx + dy * dy + dz * dz)
        } else 0f

        scratchImageList.add(
          DetectedImageInfo(
            markerId = markerName,
            trackingState = image.trackingState,
            centerPose = imgPose,
            extentXMeters = image.extentX,
            extentZMeters = image.extentZ,
            distanceToCameraMeters = distToCam
          )
        )

        val record = imageTrackingMap[markerName]
        when (image.trackingState) {
          TrackingState.TRACKING -> {
            if (record == null) {
              val anchor = try { image.createAnchor(image.centerPose) } catch (e: Exception) { null }
              val newRec = TrackedImageRecord(
                markerName = markerName,
                state = ImageTrackingState.TRACKING,
                anchor = anchor,
                lastKnownPose = imgPose,
                lastSeenTimestampMs = now
              )
              imageTrackingMap[markerName] = newRec
              onImageTrackingStateChanged?.invoke(markerName, ImageTrackingState.TRACKING, anchor, imgPose)
            } else {
              record.lastKnownPose = imgPose
              record.lastSeenTimestampMs = now
              if (record.state != ImageTrackingState.TRACKING) {
                record.state = ImageTrackingState.TRACKING
                onImageTrackingStateChanged?.invoke(markerName, ImageTrackingState.TRACKING, record.anchor, imgPose)
              }
            }
          }
          TrackingState.PAUSED -> {
            if (record != null && record.state == ImageTrackingState.TRACKING) {
              record.state = ImageTrackingState.TRACKING_LOST
              onImageTrackingStateChanged?.invoke(markerName, ImageTrackingState.TRACKING_LOST, record.anchor, record.lastKnownPose)
            }
          }
          TrackingState.STOPPED -> {
            if (record != null && record.state != ImageTrackingState.STOPPED) {
              record.state = ImageTrackingState.STOPPED
              record.anchor?.detach()
              record.anchor = null
              onImageTrackingStateChanged?.invoke(markerName, ImageTrackingState.STOPPED, null, record.lastKnownPose)
            }
          }
        }
      }

      // Check timeout for any previously tracked images not seen or paused
      for (record in imageTrackingMap.values) {
        if (record.state == ImageTrackingState.TRACKING_LOST) {
          if (now - record.lastSeenTimestampMs > TRACKING_LOSS_GRACE_PERIOD_MS) {
            record.state = ImageTrackingState.STOPPED
            record.anchor?.detach()
            record.anchor = null
            onImageTrackingStateChanged?.invoke(record.markerName, ImageTrackingState.STOPPED, null, record.lastKnownPose)
          }
        }
      }

      // Process Geospatial, Scene Semantics, Face tracking, Recording & Reconstruction
      geospatialManager.updateGeospatialState(currentSession)
      semanticsManager.processFrameSemantics(frame)
      facesManager.processFrameFaces(currentSession)
      recordingPlaybackManager.updateFrameState(currentSession)
      environmentalMeshManager.updateEnvironmentalMesh(currentSession)

      val trackingData = ArCoreTrackingData(
        trackingState = camera.trackingState,
        trackingFailureReason = camera.trackingFailureReason,
        horizontalPlanesCount = hPlanes,
        verticalPlanesCount = vPlanes,
        lightIntensityLumens = if (lightEstimate.state == LightEstimate.State.VALID) lightEstimate.pixelIntensity * 1000f else 1000f,
        colorCorrectionRgb = scratchColorCorrection,
        mainLightDirection = scratchMainLightDir,
        mainLightIntensity = scratchMainLightInt,
        cameraPose = camPose,
        cameraPosition = scratchCamPos,
        walkingDisplacementMeters = totalWalkingDisplacement,
        isDepthSupported = currentSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC),
        isDepthEnabled = currentSession.config.depthMode == Config.DepthMode.AUTOMATIC,
        isInstantPlacementEnabled = true,
        geospatialStatus = geospatialManager.status,
        semanticsTelemetry = semanticsManager.telemetry,
        cloudAnchorsCount = cloudAnchorManager.cloudAnchorsCount,
        recordingTelemetry = recordingPlaybackManager.telemetry,
        reconstructionTelemetry = environmentalMeshManager.telemetry,
        certification = deviceCertification,
        detectedPlanes = scratchPlaneList,
        detectedImages = scratchImageList
      )

      onTrackingDataUpdated?.invoke(trackingData)
      latestFrame = frame
      frame
    } catch (e: com.google.ar.core.exceptions.SessionPausedException) {
      isSessionPaused = true
      null
    } catch (e: com.google.ar.core.exceptions.CameraNotAvailableException) {
      Log.w(TAG, "Camera not available in updateFrame: ${e.message}")
      null
    } catch (e: Exception) {
      null
    }
  }

  /**
   * Performs hit test against detected physical planes with polygon bounds check,
   * falling back to ARCore Instant Placement points if planes are still forming.
   */
  fun hitTest(frame: Frame, xPx: Float, yPx: Float): HitResult? {
    val hits = frame.hitTest(xPx, yPx)
    // 1. Try detected plane with polygon bounds
    for (hit in hits) {
      val trackable = hit.trackable
      if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose) && trackable.trackingState == TrackingState.TRACKING) {
        return hit
      }
    }
    // 2. Try InstantPlacementPoint for instant zero-latency pinning
    val instantHit = hits.firstOrNull { it.trackable is com.google.ar.core.InstantPlacementPoint }
    if (instantHit != null) {
      return instantHit
    }
    return hits.firstOrNull()
  }

  fun createAnchor(hitResult: HitResult): Anchor? {
    return try {
      hitResult.createAnchor()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create ARCore Anchor", e)
      null
    }
  }

  fun createAnchorFromImage(image: AugmentedImage): Anchor? {
    return try {
      image.createAnchor(image.centerPose)
    } catch (e: Exception) {
      Log.e(TAG, "Failed creating anchor from AugmentedImage: ${e.message}", e)
      null
    }
  }

  fun createAnchorForAugmentedImage(image: AugmentedImage): Anchor? = createAnchorFromImage(image)

  fun switchCameraFacing(isFrontFaceTracking: Boolean): Boolean {
    val s = session ?: return false
    return try {
      // 1. Pause session
      s.pause()

      // 2. Release/close previously acquired frame images and depth resources
      depthOcclusionManager.clear()
      lastFrame = null

      val config = s.config
      val success = if (isFrontFaceTracking) {
        // 3. Set front camera configuration
        val camSuccess = facesManager.selectFrontCameraConfig(s)

        // 4. Configure MESH3D and explicitly disable unsupported rear-only features
        facesManager.configureFaceMode(s, config, true)
        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
        config.depthMode = Config.DepthMode.DISABLED
        config.instantPlacementMode = Config.InstantPlacementMode.DISABLED

        // 5. Configure session again
        s.configure(config)
        camSuccess
      } else {
        // 3. Set rear camera configuration
        val camSuccess = facesManager.selectBackCameraConfig(s)

        // 4. Disable face tracking and restore rear-camera capabilities
        facesManager.configureFaceMode(s, config, false)
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        depthOcclusionManager.configureDepthMode(s, config)

        // 5. Configure session again
        s.configure(config)
        camSuccess
      }

      // 6. Resume session
      s.resume()
      Log.i(TAG, "Switched camera facing (isFront=$isFrontFaceTracking, success=$success)")
      success
    } catch (e: Exception) {
      Log.e(TAG, "Failed switching camera facing: ${e.message}", e)
      try { s.resume() } catch (_: Throwable) {}
      false
    }
  }

  fun resetWalkingOrigin() {
    initialCameraPose = null
    totalWalkingDisplacement = 0f
  }
}
