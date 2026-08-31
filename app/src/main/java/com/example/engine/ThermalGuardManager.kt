package com.example.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Monitors device thermal status and dynamically triggers rendering quality adaptation
 * (MSAA downscaling, FXAA fallback, and thermal warnings) to avoid thermal throttling.
 */
class ThermalGuardManager(
  private val context: Context,
  private val onThermalStateChanged: (statusString: String, isThrottled: Boolean) -> Unit
) {

  companion object {
    private const val TAG = "ThermalGuardManager"
  }

  private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
  private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

  fun startMonitoring() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
      thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        val (statusStr, isThrottled) = when (status) {
          PowerManager.THERMAL_STATUS_NONE -> "Nominal (Safe)" to false
          PowerManager.THERMAL_STATUS_LIGHT -> "Light Warmth" to false
          PowerManager.THERMAL_STATUS_MODERATE -> "Moderate Heat (Scale MSAA)" to true
          PowerManager.THERMAL_STATUS_SEVERE -> "Severe Heat (Throttling)" to true
          PowerManager.THERMAL_STATUS_CRITICAL -> "Critical Thermal State" to true
          PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency Cooldown" to true
          PowerManager.THERMAL_STATUS_SHUTDOWN -> "Thermal Shutdown" to true
          else -> "Normal" to false
        }
        Log.i(TAG, "Thermal Status changed: $statusStr (Throttled: $isThrottled)")
        onThermalStateChanged(statusStr, isThrottled)
      }
      powerManager.addThermalStatusListener(thermalListener!!)
    } else {
      onThermalStateChanged("Normal (API < 29)", false)
    }
  }

  fun stopMonitoring() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null && thermalListener != null) {
      powerManager.removeThermalStatusListener(thermalListener!!)
      thermalListener = null
    }
  }
}
