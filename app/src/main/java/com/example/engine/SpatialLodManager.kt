package com.example.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Level of Detail (LOD) level definition.
 * LOD_0: Highest fidelity (Full geometry, 60fps bone matrix updates, full PBR reflections)
 * LOD_1: Medium fidelity (Subsampled animation updates, standard PBR)
 * LOD_2: Low fidelity (Simplified transform evaluations, reduced update frequency)
 */
enum class LodLevel(val levelIndex: Int, val distanceThresholdMeters: Float, val updateSkipFrames: Int) {
  LOD_0(0, 2.5f, 1),
  LOD_1(1, 6.0f, 2),
  LOD_2(2, 15.0f, 4)
}

/**
 * Production-grade Screen-Space & Distance-based Dynamic LOD Manager.
 * Evaluates camera-to-anchor Euclidean distance and screen-space footprint (projected bounding radius in pixels)
 * to assign optimal LOD levels and animation update rates for high scene frame rates.
 */
class SpatialLodManager {

  companion object {
    private const val TAG = "SpatialLodManager"
  }

  // Active LOD states per exhibit ID
  private val exhibitLodMap = mutableMapOf<String, LodLevel>()
  private val exhibitFrameCounters = mutableMapOf<String, Int>()

  var activeLod0Count = 0
    private set
  var activeLod1Count = 0
    private set
  var activeLod2Count = 0
    private set

  /**
   * Calculates the appropriate LOD level for a 3D exhibit based on camera distance and screen dimensions.
   */
  fun evaluateLod(
    exhibitId: String,
    cameraPos: FloatArray,
    objectPos: FloatArray,
    boundingRadiusMeters: Float,
    screenWidthPx: Int,
    screenHeightPx: Int,
    fovDegrees: Float = 60.0f
  ): LodLevel {
    val dx = objectPos[0] - cameraPos[0]
    val dy = objectPos[1] - cameraPos[1]
    val dz = objectPos[2] - cameraPos[2]
    val distance = sqrt(dx * dx + dy * dy + dz * dz)

    // Calculate Screen-Space Projected Radius in Pixels
    val focalLengthPx = (screenHeightPx / 2.0f) / Math.tan(Math.toRadians(fovDegrees / 2.0)).toFloat()
    val projectedRadiusPx = if (distance > 0.05f) {
      (boundingRadiusMeters / distance) * focalLengthPx
    } else {
      screenHeightPx.toFloat()
    }

    val lod = when {
      distance < LodLevel.LOD_0.distanceThresholdMeters || projectedRadiusPx > 300f -> LodLevel.LOD_0
      distance < LodLevel.LOD_1.distanceThresholdMeters || projectedRadiusPx > 100f -> LodLevel.LOD_1
      else -> LodLevel.LOD_2
    }

    exhibitLodMap[exhibitId] = lod
    updateLodCounts()
    return lod
  }

  /**
   * Determines if the exhibit's skeletal animation / morphs should update on this frame
   * based on its active LOD skip rate.
   */
  fun shouldUpdateAnimation(exhibitId: String): Boolean {
    val lod = exhibitLodMap[exhibitId] ?: LodLevel.LOD_0
    val counter = (exhibitFrameCounters[exhibitId] ?: 0) + 1
    exhibitFrameCounters[exhibitId] = counter

    return (counter % lod.updateSkipFrames) == 0
  }

  fun getLodForExhibit(exhibitId: String): LodLevel {
    return exhibitLodMap[exhibitId] ?: LodLevel.LOD_0
  }

  fun clear() {
    exhibitLodMap.clear()
    exhibitFrameCounters.clear()
    updateLodCounts()
  }

  private fun updateLodCounts() {
    var l0 = 0
    var l1 = 0
    var l2 = 0
    for (lod in exhibitLodMap.values) {
      when (lod) {
        LodLevel.LOD_0 -> l0++
        LodLevel.LOD_1 -> l1++
        LodLevel.LOD_2 -> l2++
      }
    }
    activeLod0Count = l0
    activeLod1Count = l1
    activeLod2Count = l2
  }
}
