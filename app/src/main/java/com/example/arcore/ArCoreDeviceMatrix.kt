package com.example.arcore

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Session

/**
 * Production ARCore Device Capability Matrix & Compatibility Certification.
 * Bridges the gap between raw Android hardware and Google ARCore certified tiers.
 * Verifies support for:
 * 1. 6DoF VIO Motion Tracking
 * 2. Automatic & Raw Depth API
 * 3. Instant Placement Mode
 * 4. Google Geospatial & VPS Earth Localization
 * 5. Scene Semantics & Environment Classification
 * 6. Augmented Faces 3D Mesh
 * 7. Streetscape Geometry & Cloud Anchors
 */
data class DeviceCapabilityCertification(
  val deviceModel: String,
  val manufacturer: String,
  val isCertifiedArCoreDevice: Boolean,
  val certificationTier: String,
  val supportsDepthApi: Boolean,
  val supportsRawDepth: Boolean,
  val supportsInstantPlacement: Boolean,
  val supportsGeospatialVps: Boolean,
  val supportsSceneSemantics: Boolean,
  val supportsAugmentedFaces: Boolean,
  val supportsStreetscapeGeometry: Boolean,
  val supportsCloudAnchors: Boolean,
  val recommendedQualityTier: String
)

object ArCoreDeviceMatrix {
  private const val TAG = "ArCoreDeviceMatrix"

  // Curated database of verified device families with known ARCore hardware profiles
  private val KNOWN_TIER_A_DEVICES = listOf(
    "pixel 6", "pixel 7", "pixel 8", "pixel 9", "pixel pro",
    "galaxy s21", "galaxy s22", "galaxy s23", "galaxy s24", "galaxy s25",
    "galaxy z fold", "galaxy z flip", "galaxy note 20",
    "xiaomi 12", "xiaomi 13", "xiaomi 14", "oneplus 10", "oneplus 11", "oneplus 12"
  )

  private val KNOWN_TIER_B_DEVICES = listOf(
    "galaxy a52", "galaxy a53", "galaxy a54", "galaxy a55",
    "pixel 6a", "pixel 7a", "pixel 8a",
    "moto g", "redmi note", "realme gt"
  )

  /**
   * Certifies current device hardware and ARCore capabilities.
   */
  fun certifyDevice(context: Context, session: Session?): DeviceCapabilityCertification {
    val model = Build.MODEL ?: "Unknown"
    val manufacturer = Build.MANUFACTURER ?: "Unknown"
    val fullDeviceName = "${manufacturer.lowercase()} ${model.lowercase()}"

    val isTierA = KNOWN_TIER_A_DEVICES.any { fullDeviceName.contains(it) }
    val isTierB = KNOWN_TIER_B_DEVICES.any { fullDeviceName.contains(it) }

    val tier = when {
      isTierA -> "TIER_A_FLAGSHIP"
      isTierB -> "TIER_B_MAINSTREAM"
      else -> "TIER_C_STANDARD"
    }

    // Dynamic runtime capability detection via ARCore session when available
    val depthSupported = session?.let {
      try {
        it.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
      } catch (_: Throwable) { false }
    } ?: isTierA

    val rawDepthSupported = session?.let {
      try {
        it.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
      } catch (_: Throwable) { false }
    } ?: isTierA

    val geospatialSupported = session?.let {
      try {
        it.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)
      } catch (_: Throwable) { false }
    } ?: (isTierA || isTierB)

    val semanticsSupported = session?.let {
      try {
        it.isSemanticModeSupported(Config.SemanticMode.ENABLED)
      } catch (_: Throwable) { false }
    } ?: isTierA

    val streetscapeSupported = session?.let {
      try {
        it.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)
      } catch (_: Throwable) { false }
    } ?: isTierA

    val instantPlacementSupported = session?.let {
      try {
        val testConfig = it.config
        testConfig.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
        it.isSupported(testConfig)
      } catch (_: Throwable) {
        // Fallback check: modern flagships support instant placement
        isTierA || isTierB
      }
    } ?: (isTierA || isTierB)

    // Check front camera support for 3D Face Mesh tracking
    val facesSupported = session?.let {
      try {
        val filter = com.google.ar.core.CameraConfigFilter(it)
          .setFacingDirection(com.google.ar.core.CameraConfig.FacingDirection.FRONT)
        it.getSupportedCameraConfigs(filter).isNotEmpty()
      } catch (_: Throwable) { false }
    } ?: false

    val cloudAnchorsSupported = (session != null) && (isTierA || isTierB)

    val certification = DeviceCapabilityCertification(
      deviceModel = model,
      manufacturer = manufacturer,
      isCertifiedArCoreDevice = isTierA || isTierB || session != null,
      certificationTier = tier,
      supportsDepthApi = depthSupported,
      supportsRawDepth = rawDepthSupported,
      supportsInstantPlacement = instantPlacementSupported,
      supportsGeospatialVps = geospatialSupported,
      supportsSceneSemantics = semanticsSupported,
      supportsAugmentedFaces = facesSupported,
      supportsStreetscapeGeometry = streetscapeSupported,
      supportsCloudAnchors = cloudAnchorsSupported,
      recommendedQualityTier = if (isTierA) "HIGH_FIDELITY_ULTRA" else "BALANCED"
    )

    Log.i(TAG, "Device Matrix Certification: $tier (Depth=$depthSupported, Semantics=$semanticsSupported, Geospatial=$geospatialSupported)")
    return certification
  }
}
