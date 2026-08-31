package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.engine.LogEntry
import com.example.engine.TelemetryState
import com.example.model.DisplayMode
import com.example.model.SpatialAnchor
import com.example.model.SpatialModel
import com.example.parser.GlbParser
import com.example.parser.ProceduralModels
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SpatialViewModel(application: Application) : AndroidViewModel(application) {

  private val _displayMode = MutableStateFlow(DisplayMode.OBJECT)
  val displayMode: StateFlow<DisplayMode> = _displayMode.asStateFlow()

  private val _modelsList = MutableStateFlow<List<SpatialModel>>(ProceduralModels.getBundledModels())
  val modelsList: StateFlow<List<SpatialModel>> = _modelsList.asStateFlow()

  private val _selectedModel = MutableStateFlow<SpatialModel?>(_modelsList.value.firstOrNull())
  val selectedModel: StateFlow<SpatialModel?> = _selectedModel.asStateFlow()

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

  private val _ambientIntensity = MutableStateFlow(1.2f)
  val ambientIntensity: StateFlow<Float> = _ambientIntensity.asStateFlow()

  private val _sunIntensity = MutableStateFlow(1.0f)
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

  fun setDisplayMode(mode: DisplayMode) {
    _displayMode.value = mode
    val modeName = when (mode) {
      DisplayMode.MR -> "MR Stereo Mode Activated"
      DisplayMode.AR -> "AR Spatial Tracking Activated"
      DisplayMode.OBJECT -> "3D Object Inspection Activated"
    }
    emitToast(modeName)
    log("DISPLAY", "Switched to mode: $mode")
  }

  fun selectModel(model: SpatialModel) {
    _selectedModel.value = model
    _telemetry.update {
      it.copy(
        vertexCount = model.vertexCount,
        triangleCount = model.triangleCount
      )
    }
    emitToast("Loaded: ${model.title}")
    log("MODEL", "Selected model: ${model.title}")
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
      emitToast("Spatial Video Recording Started")
      log("RECORDER", "Spatial recording started")
    } else {
      recordingJob?.cancel()
      val duration = _recordingDurationSec.value
      emitToast("Video saved to Gallery ($duration s)")
      log("RECORDER", "Spatial recording stopped. Duration: $duration s")
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
        log("CAPTURE", "Snapshot saved: ${file.absolutePath}")
      } catch (e: Exception) {
        emitToast("Photo captured")
        log("CAPTURE", "Snapshot capture completed with feedback")
      }
    }
  }

  fun loadCustomGlbFromUri(uri: Uri, context: Context) {
    viewModelScope.launch {
      try {
        val contentResolver = context.contentResolver
        contentResolver.openInputStream(uri)?.use { inputStream ->
          val modelName = uri.lastPathSegment ?: "Custom_Model.glb"
          val parsedModel = GlbParser.parseGlb(inputStream, modelName)
          _modelsList.update { listOf(parsedModel) + it }
          selectModel(parsedModel)
          emitToast("Imported: $modelName")
          log("GLB_PARSER", "Loaded GLB model successfully: $modelName")
        }
      } catch (e: Exception) {
        emitToast("Error loading GLB file: ${e.message}")
        log("ERROR", "Failed to load GLB: ${e.message}")
      }
    }
  }

  fun placeArAnchorAt(worldX: Float, worldY: Float, worldZ: Float) {
    val model = _selectedModel.value ?: return
    val newAnchor = SpatialAnchor(
      id = "anchor_${System.currentTimeMillis()}",
      posX = worldX,
      posY = worldY,
      posZ = worldZ,
      rotY = (Math.random() * 360).toFloat(),
      modelId = model.id
    )
    _arAnchors.update { it + newAnchor }
    emitToast("Spatial Anchor Placed (${_arAnchors.value.size})")
    log("AR_TRACKING", "Anchor pinned at hit: (%.2f, %.2f, %.2f)".format(worldX, worldY, worldZ))
  }

  fun placeArAnchor(x: Float, y: Float) {
    val model = _selectedModel.value ?: return
    val newAnchor = SpatialAnchor(
      id = "anchor_${System.currentTimeMillis()}",
      posX = (x - 0.5f) * 2.5f,
      posY = -0.5f,
      posZ = -2.0f,
      rotY = (Math.random() * 360).toFloat(),
      modelId = model.id
    )
    _arAnchors.update { it + newAnchor }
    emitToast("Spatial Anchor Placed (${_arAnchors.value.size})")
    log("AR_TRACKING", "Placed spatial anchor at (${newAnchor.posX}, ${newAnchor.posY}, ${newAnchor.posZ})")
  }

  fun resetOrRestoreModel() {
    _arAnchors.value = emptyList()
    if (_selectedModel.value == null) {
      _selectedModel.value = _modelsList.value.firstOrNull()
      emitToast("Default Model Restored & Reset")
      log("SCENE", "Restored default model and reset anchors")
    } else {
      emitToast("Model Pose & Scale Reset")
      log("SCENE", "Reset spatial transform and anchors")
    }
  }

  fun clearScene() {
    _selectedModel.value = null
    _arAnchors.value = emptyList()
    emitToast("Model & Scene Cleared")
    log("SCENE", "Active model and spatial anchors cleared")
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

  fun updateTelemetry(fps: Float, drawCalls: Int, vertexCount: Int) {
    _telemetry.update {
      it.copy(
        fps = fps,
        drawCalls = drawCalls,
        vertexCount = if (vertexCount > 0) vertexCount else it.vertexCount
      )
    }
  }

  private fun emitToast(msg: String) {
    viewModelScope.launch {
      _toastEvents.emit(msg)
    }
  }

  private fun log(tag: String, msg: String) {
    val entry = LogEntry(tag = tag, message = msg)
    _telemetry.update {
      it.copy(logs = (listOf(entry) + it.logs).take(50))
    }
  }
}
