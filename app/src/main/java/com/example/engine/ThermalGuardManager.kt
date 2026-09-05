package com.example.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

enum class ThermalQualityLevel(
  val label: String,
  val msaaSamples: Int,
  val enableFxaa: Boolean,
  val resolutionScale: Float,
  val targetFps: Int,
  val allowHeavyFeatures: Boolean,
  val isThrottled: Boolean
) {
  HIGH("High Quality (Nominal)", 2, true, 1.0f, 60, true, false),
  MEDIUM("Medium Quality (Moderate Heat)", 1, true, 0.9f, 45, true, true),
  LOW("Low Quality (Severe Heat)", 1, false, 0.75f, 30, false, true),
  EMERGENCY("Emergency Cooldown", 1, false, 0.5f, 20, false, true)
}

/**
 * Monitors device thermal status and dynamically triggers staged rendering quality adaptation
 * (HIGH, MEDIUM, LOW, EMERGENCY) with hysteresis to prevent quality oscillation.
 */
class ThermalGuardManager(
  private val context: Context,
  private val onQualityChanged: (level: ThermalQualityLevel, statusString: String) -> Unit
) {

  companion object {
    private const val TAG = "ThermalGuardManager"
    private const val HYSTERESIS_DOWNGRADE_MS = 2500L
    private const val HYSTERESIS_UPGRADE_MS = 5000L
  }

  private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
  private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

  private var currentLevel: ThermalQualityLevel = ThermalQualityLevel.HIGH
  private var pendingLevel: ThermalQualityLevel = ThermalQualityLevel.HIGH
  private var pendingLevelTimestamp = 0L

  fun startMonitoring() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
      thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        val rawLevel = when (status) {
          PowerManager.THERMAL_STATUS_NONE -> ThermalQualityLevel.HIGH
          PowerManager.THERMAL_STATUS_LIGHT -> ThermalQualityLevel.HIGH
          PowerManager.THERMAL_STATUS_MODERATE -> ThermalQualityLevel.MEDIUM
          PowerManager.THERMAL_STATUS_SEVERE -> ThermalQualityLevel.LOW
          PowerManager.THERMAL_STATUS_CRITICAL,
          PowerManager.THERMAL_STATUS_EMERGENCY,
          PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalQualityLevel.EMERGENCY
          else -> ThermalQualityLevel.HIGH
        }
        evaluateThermalTransition(rawLevel, "Thermal Status: $status")
      }
      powerManager.addThermalStatusListener(thermalListener!!)
    } else {
      onQualityChanged(ThermalQualityLevel.HIGH, "Nominal (Standard)")
    }
  }

  private fun evaluateThermalTransition(targetLevel: ThermalQualityLevel, statusMessage: String) {
    val now = System.currentTimeMillis()

    // If target level is worse (downgrade), respond faster (HYSTERESIS_DOWNGRADE_MS)
    // If target level is better (upgrade), wait longer (HYSTERESIS_UPGRADE_MS)
    if (targetLevel != currentLevel) {
      if (targetLevel != pendingLevel) {
        pendingLevel = targetLevel
        pendingLevelTimestamp = now
      } else {
        val requiredTime = if (targetLevel.ordinal > currentLevel.ordinal) {
          HYSTERESIS_DOWNGRADE_MS
        } else {
          HYSTERESIS_UPGRADE_MS
        }

        if (now - pendingLevelTimestamp >= requiredTime || targetLevel == ThermalQualityLevel.EMERGENCY) {
          currentLevel = targetLevel
          Log.i(TAG, "Thermal Guard transition to ${currentLevel.label} (msaa=${currentLevel.msaaSamples}, scale=${currentLevel.resolutionScale})")
          onQualityChanged(currentLevel, statusMessage)
        }
      }
    } else {
      pendingLevel = currentLevel
    }
  }

  fun stopMonitoring() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null && thermalListener != null) {
      powerManager.removeThermalStatusListener(thermalListener!!)
      thermalListener = null
    }
  }
}
