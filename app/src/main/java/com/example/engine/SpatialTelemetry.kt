package com.example.engine

data class TelemetryState(
  val fps: Float = 60.0f,
  val drawCalls: Int = 12,
  val vertexCount: Int = 4280,
  val triangleCount: Int = 1426,
  val ramUsageMb: Long = 48L,
  val isGpuAccelerated: Boolean = true,
  val arTrackingStatus: String = "Tracking OK",
  val thermalStatus: String = "Nominal",
  val logs: List<LogEntry> = emptyList()
)

data class LogEntry(
  val timestamp: Long = System.currentTimeMillis(),
  val level: String = "INFO",
  val tag: String,
  val message: String
)
