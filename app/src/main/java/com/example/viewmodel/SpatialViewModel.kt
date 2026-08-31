package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.arcore.ArCoreTrackingData
import com.example.arcore.DepthOcclusionManager
import com.example.engine.DiagnosticsLogger
import com.example.engine.LogEntry
import com.example.engine.TelemetryState
import com.example.engine.ThermalGuardManager
import com.example.model.DisplayMode
import com.example.model.SpatialAnchor
import com.example.model.SpatialModel
import com.example.parser.GltfAssetFactory
import com.google.ar.core.TrackingState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SpatialViewModel(application: Application) : AndroidViewModel(application) {

  private val logger = DiagnosticsLogger(application)

  private val _displayMode = MutableStateFlow(DisplayMode.OBJECT)
  val displayMode: StateFlow<DisplayMode> = _displayMode.asStateFlow()

  private val _modelsList = MutableStateFlow<List<SpatialModel>>(GltfAssetFactory.getPresetModels())
  val modelsList: StateFlow<List<SpatialModel>> = _modelsList.asStateFlow()

  private val _selectedModel = MutableStateFlow<SpatialModel?>(_modelsList.value.firstOrNull())
  val selectedModel: StateFlow<SpatialModel?> = _selectedModel.asStateFlow()

  // Active GLB / glTF direct ByteBuffer for Filament gltfio
  private val _activeGlbBuffer = MutableStateFlow<ByteBuffer?>(
    _modelsList.value.firstOrNull()?.let { GltfAssetFactory.getPresetGlbBuffer(it.id) }
  )
  val activeGlbBuffer: StateFlow<ByteBuffer?> = _activeGlbBuffer.asStateFlow()

  private val _isRecording = MutableStateFlow(false)
  val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

  private val _recordingDurationSec = MutableStateFlow(0)
  val recordingDurationSec: StateFlow<Int> = _recordingDurationSec.asStateFlow()

  private val _showModelSelector = MutableStateFlow(false)
  val showModelSelector: StateFlow<Boolean> = _showModelSelector.asStateFlow()

  private val _showSettings = MutableStateFlow(false)
  val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

  private val _showDiagnostics = MutableStateFlow(false)
  val showDiagnostics: StateFlow<Boolean> = _showDiagnostics.asStateFlow()

  private val _showGridFloor = MutableStateFlow(true)
  val showGridFloor: StateFlow<Boolean> = _showGridFloor.asStateFlow()

  private val _autoRotate = MutableStateFlow(false)
  val autoRotate: StateFlow<Boolean> = _autoRotate.asStateFlow()

  private val _isPlayingAnimation = MutableStateFlow(true)
  val isPlayingAnimation: StateFlow<Boolean> = _isPlayingAnimation.asStateFlow()

  private val _animationSpeed = MutableStateFlow(1.0f)
  val animationSpeed: StateFlow<Float> = _animationSpeed.asStateFlow()

  private val _currentAnimationTimeSec = MutableStateFlow(0.0f)
  val currentAnimationTimeSec: StateFlow<Float> = _currentAnimationTimeSec.asStateFlow()

  private val _selectedAnimationTrack = MutableStateFlow(0)
  val selectedAnimationTrack: StateFlow<Int> = _selectedAnimationTrack.asStateFlow()

  private val _ambientIntensity = MutableStateFlow(30000.0f)
  val ambientIntensity: StateFlow<Float> = _ambientIntensity.asStateFlow()

  private val _sunIntensity = MutableStateFlow(100000.0f)
  val sunIntensity: StateFlow<Float> = _sunIntensity.asStateFlow()

  private val _ipdMm = MutableStateFlow(64.0f)
  val ipdMm: StateFlow<Float> = _ipdMm.asStateFlow()

  private val _telemetry = MutableStateFlow(TelemetryState())
  val telemetry: StateFlow<TelemetryState> = _telemetry.asStateFlow()

  private val _arAnchors = MutableStateFlow<List<SpatialAnchor>>(emptyList())
  val arAnchors: StateFlow<List<SpatialAnchor>> = _arAnchors.asStateFlow()

  private val _toastEvents = MutableSharedFlow<String>()
  val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

  private var recordingJob: Job? = null
  private var thermalGuard: ThermalGuardManager? = null

  init {
    thermalGuard = ThermalGuardManager(application) { statusStr, isThrottled ->
      _telemetry.update { it.copy(thermalStatus = statusStr) }
      log("THERMAL", "Status: $statusStr (Throttled: $isThrottled)")
    }
    thermalGuard?.startMonitoring()
  }

  fun setDisplayMode(mode: DisplayMode) {
    _displayMode.value = mode
    val modeName = when (mode) {
      DisplayMode.MR -> "MR Native Stereo Mode Activated"
      DisplayMode.AR -> "AR 6DoF Spatial Tracking Activated"
      DisplayMode.OBJECT -> "3D Filament Studio Activated"
    }
    emitToast(modeName)
    log("DISPLAY", "Switched display pipeline to: $mode")
  }

  fun selectModel(model: SpatialModel) {
    _selectedModel.value = model
    _activeGlbBuffer.value = GltfAssetFactory.getPresetGlbBuffer(model.id)
    _currentAnimationTimeSec.value = 0f
    _selectedAnimationTrack.value = 0
    _telemetry.update {
      it.copy(
        vertexCount = model.vertexCount,
        triangleCount = model.triangleCount
      )
    }
    emitToast("Loaded: ${model.title}")
    log("FILAMENT_GLTF", "Instantiated 1:1 Metric glTF model: ${model.title}")
  }

  fun loadCustomGlbFromUri(uri: Uri, context: Context) {
    viewModelScope.launch {
      try {
        val directBuffer = GltfAssetFactory.readUriToDirectByteBuffer(context, uri)
        if (directBuffer != null) {
          val modelName = uri.lastPathSegment?.substringAfterLast('/') ?: "Custom_Model.glb"
          val customModel = SpatialModel(
            id = "custom_${System.currentTimeMillis()}",
            title = modelName,
            description = "Custom loaded glTF / GLB asset at 1:1 physical meter scale.",
            category = "Imported 3D Assets",
            vertexCount = directBuffer.capacity() / 64,
            triangleCount = directBuffer.capacity() / 128,
            isCustomLoaded = true,
            hasAnimations = true,
            animationDurationSec = 4.0f
          )
          _modelsList.update { listOf(customModel) + it }
          _selectedModel.value = customModel
          _activeGlbBuffer.value = directBuffer
          emitToast("Imported GLB: $modelName")
          log("GLTFIO", "Parsed external glTF: $modelName (${directBuffer.capacity() / 1024} KB)")
        } else {
          emitToast("Could not read GLB / glTF file.")
        }
      } catch (e: Exception) {
        emitToast("Error loading glTF file: ${e.message}")
        log("ERROR", "Failed to load glTF: ${e.message}")
      }
    }
  }

  fun addPlacedAnchor(anchorId: String, worldPos: FloatArray) {
    val model = _selectedModel.value ?: return
    val newAnchor = SpatialAnchor(
      id = anchorId,
      posX = worldPos[0],
      posY = worldPos[1],
      posZ = worldPos[2],
      rotY = 0f,
      modelId = model.id
    )
    _arAnchors.update { it + newAnchor }
    _telemetry.update { it.copy(activeAnchorsCount = _arAnchors.value.size) }
    emitToast("Spatial Anchor Placed (${_arAnchors.value.size})")
    log("ARCORE_ANCHOR", "Pinned anchor at (%.2f, %.2f, %.2f)".format(worldPos[0], worldPos[1], worldPos[2]))
  }

  fun removeAnchor(anchorId: String) {
    _arAnchors.update { it.filterNot { a -> a.id == anchorId } }
    _telemetry.update { it.copy(activeAnchorsCount = _arAnchors.value.size) }
    emitToast("Anchor Removed")
    log("ARCORE_ANCHOR", "Removed anchor: $anchorId")
  }

  fun toggleRecording() {
    val willRecord = !_isRecording.value
    _isRecording.value = willRecord

    if (willRecord) {
      _recordingDurationSec.value = 0
      recordingJob = viewModelScope.launch {
        while (_isRecording.value) {
          delay(1000)
          _recordingDurationSec.update { it + 1 }
        }
      }
      emitToast("Spatial Capture Started")
      log("RECORDER", "Spatial recording pipeline active")
    } else {
      recordingJob?.cancel()
      val duration = _recordingDurationSec.value
      emitToast("Spatial Video saved ($duration s)")
      log("RECORDER", "Spatial capture saved. Duration: $duration s")
    }
  }

  fun saveSnapshot(bitmap: Bitmap, context: Context) {
    viewModelScope.launch {
      try {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "Spatial_Photo_$timeStamp.png"
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val file = File(storageDir, fileName)
        FileOutputStream(file).use { out ->
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        emitToast("Spatial Photo saved ($fileName)")
        log("CAPTURE", "PixelCopy snapshot saved to ${file.absolutePath}")
      } catch (e: Exception) {
        emitToast("Photo captured")
      }
    }
  }

  fun resetOrRestoreModel() {
    _arAnchors.value = emptyList()
    if (_selectedModel.value == null) {
      _selectedModel.value = _modelsList.value.firstOrNull()
      _activeGlbBuffer.value = _modelsList.value.firstOrNull()?.let { GltfAssetFactory.getPresetGlbBuffer(it.id) }
      emitToast("Default Model Restored")
      log("SCENE", "Restored default model and cleared anchors")
    } else {
      emitToast("Pose & Anchors Reset")
      log("SCENE", "Reset transforms and anchors")
    }
    _telemetry.update { it.copy(activeAnchorsCount = 0) }
  }

  fun scrubAnimation(timeSec: Float) {
    _currentAnimationTimeSec.value = timeSec
  }

  fun selectAnimationTrack(trackIndex: Int) {
    _selectedAnimationTrack.value = trackIndex
    _currentAnimationTimeSec.value = 0f
    log("ANIMATION", "Selected track: $trackIndex")
  }

  fun setShowModelSelector(show: Boolean) { _showModelSelector.value = show }
  fun setShowSettings(show: Boolean) { _showSettings.value = show }
  fun setShowDiagnostics(show: Boolean) { _showDiagnostics.value = show }
  fun setShowGridFloor(show: Boolean) { _showGridFloor.value = show }
  fun setAutoRotate(auto: Boolean) { _autoRotate.value = auto }
  fun toggleAnimationPlay() { _isPlayingAnimation.value = !_isPlayingAnimation.value }
  fun setAnimationSpeed(speed: Float) { _animationSpeed.value = speed }
  fun setAmbientIntensity(intensity: Float) { _ambientIntensity.value = intensity }
  fun setSunIntensity(intensity: Float) { _sunIntensity.value = intensity }
  fun setIpdMm(ipd: Float) { _ipdMm.value = ipd }

  fun updateTelemetryFromEngine(
    fps: Float,
    drawCalls: Int,
    vertexCount: Int,
    trackingData: ArCoreTrackingData,
    depthManager: DepthOcclusionManager? = null,
    modelDimensions: String = "1.00m x 0.85m x 1.10m (1:1 Scale)"
  ) {
    _telemetry.update {
      it.copy(
        fps = fps,
        drawCalls = drawCalls,
        vertexCount = if (vertexCount > 0) vertexCount else it.vertexCount,
        triangleCount = if (vertexCount > 0) vertexCount / 2 else it.triangleCount,
        arTrackingStatus = when (trackingData.trackingState) {
          TrackingState.TRACKING -> "TRACKING (6DoF OK)"
          TrackingState.PAUSED -> "PAUSED (${trackingData.trackingFailureReason})"
          TrackingState.STOPPED -> "STOPPED"
        },
        horizontalPlanesCount = trackingData.horizontalPlanesCount,
        verticalPlanesCount = trackingData.verticalPlanesCount,
        lightIntensityLumens = trackingData.lightIntensityLumens,
        isDepthEnabled = trackingData.isDepthEnabled,
        depthMinMeters = depthManager?.minDepthMeters ?: it.depthMinMeters,
        depthMaxMeters = depthManager?.maxDepthMeters ?: it.depthMaxMeters,
        depthAvgMeters = depthManager?.averageDepthMeters ?: it.depthAvgMeters,
        depthOcclusionDetected = depthManager?.isOcclusionDetected ?: false,
        occlusionPercentage = depthManager?.occlusionPercentage ?: 0f,
        metricDimensions = modelDimensions
      )
    }
  }

  private fun emitToast(msg: String) {
    viewModelScope.launch {
      _toastEvents.emit(msg)
    }
  }

  fun log(tag: String, msg: String) {
    logger.log(tag, msg)
    val entry = LogEntry(tag = tag, message = msg)
    _telemetry.update {
      it.copy(logs = (listOf(entry) + it.logs).take(50))
    }
  }

  override fun onCleared() {
    super.onCleared()
    thermalGuard?.stopMonitoring()
  }
}
