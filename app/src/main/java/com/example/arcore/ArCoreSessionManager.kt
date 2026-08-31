package com.example.arcore

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
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
  val isDepthSupported: Boolean = false,
  val isDepthEnabled: Boolean = false,
  val detectedPlanes: List<DetectedPlaneInfo> = emptyList()
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
 * plane detection, environmental HDR light estimation, and depth sensing.
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

  var onTrackingDataUpdated: ((ArCoreTrackingData) -> Unit)? = null

  /**
   * Checks if ARCore is supported on this device.
   */
  fun checkAvailability(activity: Activity, onResult: (Boolean) -> Unit) {
    try {
      val availability = ArCoreApk.getInstance().checkAvailability(context)
      if (availability.isTransient) {
        // Re-check after transient state
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
   * Initializes or resumes the ARCore Session with optimal configuration:
   * Autofocus, Horizontal + Vertical planes, Environmental HDR, and Depth.
   */
  fun resumeSession(activity: Activity): Boolean {
    if (session == null) {
      try {
        val installStatus = ArCoreApk.getInstance().requestInstall(activity, userRequestedInstall)
        if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
          userRequestedInstall = false
          return false
        }

        val newSession = Session(activity)
        val config = Config(newSession).apply {
          focusMode = Config.FocusMode.AUTO
          planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
          lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

          if (newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            depthMode = Config.DepthMode.AUTOMATIC
          } else {
            depthMode = Config.DepthMode.DISABLED
          }
        }
        newSession.configure(config)
        session = newSession
        isConfigured = true
        Log.i(TAG, "ARCore Session initialized with Environmental HDR & Depth: ${config.depthMode}")
      } catch (e: UnavailableArcoreNotInstalledException) {
        Log.e(TAG, "ARCore not installed", e)
        return false
      } catch (e: UnavailableUserDeclinedInstallationException) {
        Log.e(TAG, "ARCore installation declined", e)
        return false
      } catch (e: UnavailableDeviceNotCompatibleException) {
        Log.e(TAG, "Device not compatible with ARCore", e)
        return false
      } catch (e: UnavailableApkTooOldException) {
        Log.e(TAG, "ARCore APK too old", e)
        return false
      } catch (e: UnavailableSdkTooOldException) {
        Log.e(TAG, "ARCore SDK too old", e)
        return false
      } catch (e: Exception) {
        Log.e(TAG, "Failed to create ARCore Session", e)
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
    } catch (e: Exception) {
      Log.w(TAG, "Error closing ARCore session: ${e.message}")
    }
  }

  /**
   * Sets the display geometry for ARCore camera aspect ratio and rotation.
   */
  fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
    session?.setDisplayGeometry(rotation, width, height)
  }

  /**
   * Sets the OpenGL texture name used by ARCore for camera background streaming.
   */
  fun setCameraTextureName(textureId: Int) {
    session?.setCameraTextureName(textureId)
  }

  /**
   * Updates the ARCore session and processes tracking data, planes, and light estimation.
   */
  fun updateFrame(): Frame? {
    val currentSession = session ?: return null
    return try {
      val frame = currentSession.update()
      val camera = frame.camera

      // Process light estimation
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

      // Collect planes
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

      val trackingData = ArCoreTrackingData(
        trackingState = camera.trackingState,
        trackingFailureReason = camera.trackingFailureReason,
        horizontalPlanesCount = hPlanes,
        verticalPlanesCount = vPlanes,
        lightIntensityLumens = if (lightEstimate.state == LightEstimate.State.VALID) lightEstimate.pixelIntensity * 1000f else 1000f,
        colorCorrectionRgb = colorCorrection,
        mainLightDirection = mainLightDir,
        mainLightIntensity = mainLightInt,
        cameraPose = if (camera.trackingState == TrackingState.TRACKING) camera.pose else null,
        isDepthSupported = currentSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC),
        isDepthEnabled = currentSession.config.depthMode == Config.DepthMode.AUTOMATIC,
        detectedPlanes = planeList
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
    // Fallback to any valid hit if not inside polygon
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
}
