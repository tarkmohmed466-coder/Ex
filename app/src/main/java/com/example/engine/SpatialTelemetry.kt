package com.example.engine

data class TelemetryState(
  val fps: Float = 60.0f,
  val drawCalls: Int = 14,
  val vertexCount: Int = 2400,
  val triangleCount: Int = 1200,
  val ramUsageMb: Long = 64L,
  val isGpuAccelerated: Boolean = true,
  val rendererEngine: String = "Google Filament gltfio (PBR)",
  val spatialTrackingEngine: String = "Google ARCore 6DoF",
  val arTrackingStatus: String = "TRACKING",
  val horizontalPlanesCount: Int = 0,
  val verticalPlanesCount: Int = 0,
  val activeAnchorsCount: Int = 0,
  val lightIntensityLumens: Float = 1000.0f,
  val isDepthEnabled: Boolean = true,
  val thermalStatus: String = "Nominal (Safe)",
  val logs: List<LogEntry> = emptyList()
)

data class LogEntry(
  val timestamp: Long = System.currentTimeMillis(),
  val level: String = "INFO",
  val tag: String,
  val message: String
)
