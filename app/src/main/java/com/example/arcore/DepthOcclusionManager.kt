package com.example.arcore

import android.media.Image
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Production-Grade ARCore Depth & Occlusion Manager.
 * Processes 16-bit raw depth buffers, calculates physical millimeter distances,
 * and performs real-time occlusion testing against virtual 3D models and anchors.
 */
class DepthOcclusionManager {

  companion object {
    private const val TAG = "DepthOcclusionManager"
  }

  var latestDepthImage: Image? = null
    private set

  var isOcclusionDetected: Boolean = false
    private set

  var averageDepthMeters: Float = 0f
    private set

  var minDepthMeters: Float = 0f
    private set

  var maxDepthMeters: Float = 0f
    private set

  var occlusionPercentage: Float = 0f
    private set

  /**
   * Processes the latest ARCore frame's depth image and calculates depth statistics.
   */
  fun processFrameDepth(frame: Frame, virtualAnchorPoses: List<Pose>) {
    var depthImage: Image? = null
    try {
      depthImage = try {
        frame.acquireDepthImage16Bits()
      } catch (e: Exception) {
        frame.acquireRawDepthImage16Bits()
      }

      if (depthImage == null) return

      val width = depthImage.width
      val height = depthImage.height
      val planes = depthImage.planes
      if (planes.isEmpty()) return

      val buffer: ByteBuffer = planes[0].buffer.order(ByteOrder.LITTLE_ENDIAN)
      val pixelStride = planes[0].pixelStride
      val rowStride = planes[0].rowStride

      var minDepth = Float.MAX_VALUE
      var maxDepth = 0f
      var depthSum = 0.0
      var sampleCount = 0

      // Sample depth grid
      val stepX = (width / 16).coerceAtLeast(1)
      val stepY = (height / 16).coerceAtLeast(1)

      for (y in 0 until height step stepY) {
        for (x in 0 until width step stepX) {
          val byteIndex = y * rowStride + x * pixelStride
          if (byteIndex + 1 < buffer.capacity()) {
            val depthMillimeters = buffer.getShort(byteIndex).toInt() and 0xFFFF
            if (depthMillimeters in 100..10000) { // Valid range 0.1m - 10m
              val depthMeters = depthMillimeters / 1000.0f
              minDepth = minOf(minDepth, depthMeters)
              maxDepth = maxOf(maxDepth, depthMeters)
              depthSum += depthMeters
              sampleCount++
            }
          }
        }
      }

      if (sampleCount > 0) {
        minDepthMeters = minDepth
        maxDepthMeters = maxDepth
        averageDepthMeters = (depthSum / sampleCount).toFloat()
      }

      // Check occlusion against virtual anchors
      var occludedAnchors = 0
      for (anchorPose in virtualAnchorPoses) {
        val anchorDist = Math.sqrt(
          (anchorPose.tx() * anchorPose.tx() +
           anchorPose.ty() * anchorPose.ty() +
           anchorPose.tz() * anchorPose.tz()).toDouble()
        ).toFloat()

        // If physical object in foreground is closer than anchor distance
        if (minDepthMeters > 0.1f && minDepthMeters < anchorDist - 0.15f) {
          occludedAnchors++
        }
      }

      isOcclusionDetected = occludedAnchors > 0
      occlusionPercentage = if (virtualAnchorPoses.isNotEmpty()) {
        (occludedAnchors.toFloat() / virtualAnchorPoses.size) * 100f
      } else {
        0f
      }

    } catch (e: NotYetAvailableException) {
      // Depth buffer not ready yet this frame
    } catch (e: Exception) {
      // Depth unsupported or transient failure
    } finally {
      depthImage?.close()
    }
  }

  /**
   * Retrieves millimeter depth value at specific normalized screen coordinates.
   */
  fun getDepthAtNormalizedCoordinates(
    frame: Frame,
    normalizedX: Float,
    normalizedY: Float
  ): Float? {
    var depthImage: Image? = null
    return try {
      depthImage = frame.acquireDepthImage16Bits()
      val buffer = depthImage.planes[0].buffer.order(ByteOrder.LITTLE_ENDIAN)
      val pixelX = (normalizedX * depthImage.width).toInt().coerceIn(0, depthImage.width - 1)
      val pixelY = (normalizedY * depthImage.height).toInt().coerceIn(0, depthImage.height - 1)

      val byteIndex = pixelY * depthImage.planes[0].rowStride + pixelX * depthImage.planes[0].pixelStride
      val depthMm = buffer.getShort(byteIndex).toInt() and 0xFFFF
      if (depthMm > 0) depthMm / 1000.0f else null
    } catch (e: Exception) {
      null
    } finally {
      depthImage?.close()
    }
  }
}
