package com.example.arcore

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
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
 * Production-grade ARCore session manager handling lifecycle, 6DoF tracking,
 * plane detection, AugmentedImageDatabase tracking, environmental HDR light estimation,
 * walking distance calculation, and depth sensing.
 */
class ArCoreSessionManager(private val context: Context) {

  companion object {
    private const val TAG = "ArCoreSessionManager"
  }

  var session: Session? = null
    private set

  var isSupported: Boolean = false
    private set

  var isConfigured: Boolean = false
    private set

  var latestFrame: Frame? = null
    private set

  private var userRequestedInstall = true

  // Initial camera position for walking distance calculation
  private var initialCameraPose: Pose? = null
  private var totalWalkingDisplacement: Float = 0f

  // Callbacks
  var onTrackingDataUpdated: ((ArCoreTrackingData) -> Unit)? = null
  var onImageMarkerDetected: ((AugmentedImage) -> Unit)? = null

  /**
   * Checks if ARCore is supported on this device.
   */
  fun checkAvailability(activity: Activity, onResult: (Boolean) -> Unit) {
    try {
      val availability = ArCoreApk.getInstance().checkAvailability(context)
      if (availability.isTransient) {
        onResult(availability.isSupported)
        return
      }
      isSupported = availability.isSupported
      onResult(isSupported)
    } catch (e: Exception) {
      Log.w(TAG, "ARCore availability check failed: ${e.message}")
      isSupported = false
      onResult(false)
    }
  }

  /**
   * Builds and populates the AugmentedImageDatabase with target marker cards from catalog.
   */
  private fun buildAugmentedImageDatabase(session: Session): AugmentedImageDatabase {
    val imageDatabase = AugmentedImageDatabase(session)
    for (exhibit in ImageMarkerCatalog.exhibits) {
      try {
        val bitmap = ImageMarkerCatalog.generateMarkerBitmap(exhibit)
        val imageIndex = imageDatabase.addImage(
          exhibit.markerId,
          bitmap,
          exhibit.physicalWidthMeters
        )
        Log.i(TAG, "Added marker to ARCore Image Database: ${exhibit.markerId} (Index $imageIndex, width ${exhibit.physicalWidthMeters}m)")
      } catch (e: Exception) {
        Log.e(TAG, "Failed adding marker ${exhibit.markerId} to image database: ${e.message}")
      }
    }
    return imageDatabase
  }

  private fun isPackageInstalled(context: Context, packageName: String): Boolean {
    return try {
      context.packageManager.getPackageInfo(packageName, 0)
      true
    } catch (e: Exception) {
      false
    }
  }

  /**
   * Initializes or resumes the ARCore Session with optimal configuration:
   * Autofocus, Horizontal + Vertical planes, AugmentedImageDatabase, Environmental HDR, and Depth.
   */
  fun resumeSession(activity: Activity): Boolean {
    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "Cannot resume ARCore: Camera permission not granted yet")
      return false
    }

    if (session == null) {
      val isArCoreInstalled = isPackageInstalled(activity, "com.google.ar.core")
      if (!isArCoreInstalled) {
        val isPlayStoreAvailable = isPackageInstalled(activity, "com.android.vending")
        if (!isPlayStoreAvailable) {
          Log.i(TAG, "ARCore APK not installed and Google Play Store unavailable. Using device sensors fallback.")
          isSupported = false
          userRequestedInstall = false
          return false
        }

        if (userRequestedInstall) {
          try {
            val installStatus = ArCoreApk.getInstance().requestInstall(activity, userRequestedInstall)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
              userRequestedInstall = false
              return false
            }
          } catch (t: Throwable) {
            Log.w(TAG, "ARCore installer request skipped: ${t.message}")
            userRequestedInstall = false
            isSupported = false
            return false
          }
        }

        // If com.google.ar.core is still not present, bypass Session creation
        if (!isPackageInstalled(activity, "com.google.ar.core")) {
          Log.i(TAG, "ARCore APK not present on system. Running in sensor-driven AR/MR mode.")
          isSupported = false
          return false
        }
      }

      try {
        val availability = ArCoreApk.getInstance().checkAvailability(context)
        if (availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
          Log.i(TAG, "Device/Emulator is not ARCore capable. Using sensor & gyro fallback.")
          isSupported = false
          return false
        }

        val newSession = Session(activity)
        val imageDatabase = buildAugmentedImageDatabase(newSession)

        val config = Config(newSession).apply {
          focusMode = Config.FocusMode.AUTO
          planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
          lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
          augmentedImageDatabase = imageDatabase

          if (newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            depthMode = Config.DepthMode.AUTOMATIC
          } else {
            depthMode = Config.DepthMode.DISABLED
          }
        }
        newSession.configure(config)
        session = newSession
        isConfigured = true
        isSupported = true
        Log.i(TAG, "ARCore Session configured with Environmental HDR, Depth: ${config.depthMode}, & ImageDatabase (${imageDatabase.numImages} targets)")
      } catch (e: UnavailableArcoreNotInstalledException) {
        Log.w(TAG, "ARCore APK not installed on this system: ${e.message}")
        userRequestedInstall = false
        isSupported = false
        return false
      } catch (e: UnavailableUserDeclinedInstallationException) {
        Log.w(TAG, "ARCore installation declined: ${e.message}")
        userRequestedInstall = false
        return false
      } catch (e: UnavailableDeviceNotCompatibleException) {
        Log.w(TAG, "Device not compatible with ARCore: ${e.message}")
        userRequestedInstall = false
        isSupported = false
        return false
      } catch (e: UnavailableApkTooOldException) {
        Log.w(TAG, "ARCore APK too old: ${e.message}")
        userRequestedInstall = false
        return false
      } catch (e: UnavailableSdkTooOldException) {
        Log.w(TAG, "ARCore SDK too old: ${e.message}")
        userRequestedInstall = false
        return false
      } catch (t: Throwable) {
        Log.w(TAG, "Bypassed ARCore session on this device/emulator: ${t.message}")
        userRequestedInstall = false
        isSupported = false
        return false
      }
    }

    try {
      session?.resume()
      return true
    } catch (e: CameraNotAvailableException) {
      Log.e(TAG, "Camera not available during ARCore resume", e)
      return false
    } catch (e: Exception) {
      Log.e(TAG, "Error resuming ARCore session", e)
      return false
    }
  }

  fun pauseSession() {
    try {
      session?.pause()
    } catch (e: Exception) {
      Log.w(TAG, "Error pausing ARCore session: ${e.message}")
    }
  }

  fun destroySession() {
    try {
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
    session?.setCameraTextureName(textureId)
  }

  /**
   * Updates the ARCore session and processes tracking data, planes, augmented images,
   * walking distance, and light estimation.
   */
  fun updateFrame(): Frame? {
    val currentSession = session ?: return null
    return try {
      val frame = currentSession.update()
      val camera = frame.camera

      // Walking Camera Tracking & Displacement
      val camPose = if (camera.trackingState == TrackingState.TRACKING) camera.pose else null
      val camPos = floatArrayOf(camPose?.tx() ?: 0f, camPose?.ty() ?: 0f, camPose?.tz() ?: 0f)

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
      val colorCorrection = FloatArray(4) { 1f }
      if (lightEstimate.state == LightEstimate.State.VALID) {
        lightEstimate.getColorCorrection(colorCorrection, 0)
      }

      val mainLightDir = FloatArray(3) { 0f }.apply { this[1] = -1f; this[2] = -0.5f }
      val mainLightInt = FloatArray(3) { 1f }
      if (lightEstimate.state == LightEstimate.State.VALID) {
        lightEstimate.environmentalHdrMainLightDirection?.let {
          System.arraycopy(it, 0, mainLightDir, 0, 3)
        }
        lightEstimate.environmentalHdrMainLightIntensity?.let {
          System.arraycopy(it, 0, mainLightInt, 0, 3)
        }
      }

      // 1. Collect Planes
      val allPlanes = currentSession.getAllTrackables(Plane::class.java)
      var hPlanes = 0
      var vPlanes = 0
      val planeList = mutableListOf<DetectedPlaneInfo>()

      for (plane in allPlanes) {
        if (plane.trackingState == TrackingState.TRACKING) {
          if (plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING || plane.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING) {
            hPlanes++
          } else if (plane.type == Plane.Type.VERTICAL) {
            vPlanes++
          }
          planeList.add(
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

      // 2. Collect Augmented Image Markers
      val allImages = currentSession.getAllTrackables(AugmentedImage::class.java)
      val imageList = mutableListOf<DetectedImageInfo>()

      for (image in allImages) {
        if (image.trackingState == TrackingState.TRACKING) {
          val imgPose = image.centerPose
          val distToCam = if (camPose != null) {
            val dx = imgPose.tx() - camPose.tx()
            val dy = imgPose.ty() - camPose.ty()
            val dz = imgPose.tz() - camPose.tz()
            sqrt(dx * dx + dy * dy + dz * dz)
          } else 0f

          imageList.add(
            DetectedImageInfo(
              markerId = image.name,
              trackingState = image.trackingState,
              centerPose = imgPose,
              extentXMeters = image.extentX,
              extentZMeters = image.extentZ,
              distanceToCameraMeters = distToCam
            )
          )

          onImageMarkerDetected?.invoke(image)
        }
      }

      val trackingData = ArCoreTrackingData(
        trackingState = camera.trackingState,
        trackingFailureReason = camera.trackingFailureReason,
        horizontalPlanesCount = hPlanes,
        verticalPlanesCount = vPlanes,
        lightIntensityLumens = if (lightEstimate.state == LightEstimate.State.VALID) lightEstimate.pixelIntensity * 1000f else 1000f,
        colorCorrectionRgb = colorCorrection,
        mainLightDirection = mainLightDir,
        mainLightIntensity = mainLightInt,
        cameraPose = camPose,
        cameraPosition = camPos,
        walkingDisplacementMeters = totalWalkingDisplacement,
        isDepthSupported = currentSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC),
        isDepthEnabled = currentSession.config.depthMode == Config.DepthMode.AUTOMATIC,
        detectedPlanes = planeList,
        detectedImages = imageList
      )

      onTrackingDataUpdated?.invoke(trackingData)
      latestFrame = frame
      frame
    } catch (e: Exception) {
      null
    }
  }

  /**
   * Performs hit test against detected physical planes or depth points.
   */
  fun hitTest(frame: Frame, xPx: Float, yPx: Float): HitResult? {
    val hits = frame.hitTest(xPx, yPx)
    for (hit in hits) {
      val trackable = hit.trackable
      if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose) && trackable.trackingState == TrackingState.TRACKING) {
        return hit
      }
    }
    return hits.firstOrNull()
  }

  /**
   * Creates a real persistent ARCore Anchor attached to a HitResult.
   */
  fun createAnchor(hitResult: HitResult): Anchor? {
    return try {
      hitResult.createAnchor()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create ARCore Anchor", e)
      null
    }
  }

  /**
   * Creates a real persistent ARCore Anchor attached to an AugmentedImage at its centerPose.
   */
  fun createAnchorForAugmentedImage(image: AugmentedImage): Anchor? {
    return try {
      image.createAnchor(image.centerPose)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create ARCore Anchor on AugmentedImage ${image.name}", e)
      null
    }
  }

  /**
   * Creates a real persistent ARCore Anchor at a specific Pose.
   */
  fun createAnchorAtPose(pose: Pose): Anchor? {
    val currentSession = session ?: return null
    return try {
      currentSession.createAnchor(pose)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create ARCore Anchor at pose", e)
      null
    }
  }

  fun resetWalkingOrigin() {
    initialCameraPose = latestFrame?.camera?.pose
    totalWalkingDisplacement = 0f
  }
}
