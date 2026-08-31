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
  val trackedImagesCount: Int = 0,
  val activeAnchorsCount: Int = 0,
  val walkingDisplacementMeters: Float = 0f,
  val lightIntensityLumens: Float = 1000.0f,
  val isDepthEnabled: Boolean = true,
  val depthMinMeters: Float = 0.3f,
  val depthMaxMeters: Float = 4.5f,
  val depthAvgMeters: Float = 1.8f,
  val depthOcclusionDetected: Boolean = false,
  val occlusionPercentage: Float = 0f,
  val metricDimensions: String = "1.00m x 0.85m x 1.10m (1:1 Scale)",
  val currentAnimationTrack: Int = 0,
  val totalAnimationTracks: Int = 1,
  val thermalStatus: String = "Nominal (Safe)",
  val logs: List<LogEntry> = emptyList()
)

data class LogEntry(
  val timestamp: Long = System.currentTimeMillis(),
  val level: String = "INFO",
  val tag: String,
  val message: String
)
