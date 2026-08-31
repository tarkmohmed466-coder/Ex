package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.engine.HapticManager
import com.example.model.DisplayMode
import com.example.renderer.SpatialSurfaceView
import com.example.ui.components.AnimationControlBar
import com.example.ui.components.BottomActionPill
import com.example.ui.components.DiagnosticsHud
import com.example.ui.components.ModelSelectorSheet
import com.example.ui.components.SettingsSheet
import com.example.ui.components.TopModePill
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.SpatialViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  private val viewModel: SpatialViewModel by viewModels()
  private var spatialSurfaceView: SpatialSurfaceView? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      MyApplicationTheme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = Color(0xFF000000)
        ) {
          MixedRealityScreen(
            viewModel = viewModel,
            onSurfaceViewCreated = { surfaceView ->
              spatialSurfaceView = surfaceView
            }
          )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    spatialSurfaceView?.resume(this)
  }

  override fun onPause() {
    spatialSurfaceView?.pause()
    super.onPause()
  }

  override fun onDestroy() {
    spatialSurfaceView?.destroy()
    super.onDestroy()
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixedRealityScreen(
  viewModel: SpatialViewModel,
  onSurfaceViewCreated: (SpatialSurfaceView) -> Unit
) {
  val context = LocalContext.current
  val activity = context as? ComponentActivity
  val scope = rememberCoroutineScope()
  val hapticManager = remember { HapticManager(context) }

  // Observed View Model States
  val displayMode by viewModel.displayMode.collectAsState()
  val modelsList by viewModel.modelsList.collectAsState()
  val selectedModel by viewModel.selectedModel.collectAsState()
  val activeGlbBuffer by viewModel.activeGlbBuffer.collectAsState()
  val isRecording by viewModel.isRecording.collectAsState()
  val recordingDurationSec by viewModel.recordingDurationSec.collectAsState()
  val showModelSelector by viewModel.showModelSelector.collectAsState()
  val showSettings by viewModel.showSettings.collectAsState()
  val showDiagnostics by viewModel.showDiagnostics.collectAsState()
  val showGridFloor by viewModel.showGridFloor.collectAsState()
  val autoRotate by viewModel.autoRotate.collectAsState()
  val isPlayingAnimation by viewModel.isPlayingAnimation.collectAsState()
  val animationSpeed by viewModel.animationSpeed.collectAsState()
  val ambientIntensity by viewModel.ambientIntensity.collectAsState()
  val sunIntensity by viewModel.sunIntensity.collectAsState()
  val ipdMm by viewModel.ipdMm.collectAsState()
  val telemetry by viewModel.telemetry.collectAsState()

  // Sheet states
  val modelSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  // Camera Permission state for AR/MR passthrough
  var hasCameraPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    )
  }

  val cameraPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { granted ->
    hasCameraPermission = granted
  }

  // GLB / glTF File Picker launcher
  val filePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
  ) { uri ->
    uri?.let { viewModel.loadCustomGlbFromUri(it, context) }
  }

  // Flash snapshot animation alpha
  val flashAnim = remember { Animatable(0f) }

  // Unified Filament + ARCore Spatial Surface View
  val spatialSurfaceView = remember {
    SpatialSurfaceView(context).apply {
      onTelemetryUpdate = { fps, drawCalls, vertexCount, trackingData ->
        viewModel.updateTelemetryFromEngine(fps, drawCalls, vertexCount, trackingData)
      }
      onAnchorPlaced = { anchor, hitPos ->
        viewModel.addPlacedAnchor("anchor_${anchor.hashCode()}", hitPos)
      }
      onSurfaceViewCreated(this)
    }
  }

  // Synchronize state with Filament Engine & ARCore Session
  LaunchedEffect(activeGlbBuffer, selectedModel) {
    val buf = activeGlbBuffer
    val model = selectedModel
    if (buf != null && model != null) {
      spatialSurfaceView.loadGlbBuffer(buf, model.title)
    }
  }

  LaunchedEffect(displayMode) {
    spatialSurfaceView.displayMode = displayMode
    if ((displayMode == DisplayMode.AR || displayMode == DisplayMode.MR) && !hasCameraPermission) {
      cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
  }

  LaunchedEffect(showGridFloor) {
    spatialSurfaceView.filamentEngine.showGrid = showGridFloor
  }

  LaunchedEffect(autoRotate) {
    spatialSurfaceView.filamentEngine.autoRotate = autoRotate
  }

  LaunchedEffect(isPlayingAnimation) {
    spatialSurfaceView.filamentEngine.isPlayingAnimation = isPlayingAnimation
  }

  LaunchedEffect(animationSpeed) {
    spatialSurfaceView.filamentEngine.animationSpeed = animationSpeed
  }

  LaunchedEffect(ambientIntensity) {
    spatialSurfaceView.filamentEngine.ambientIntensity = ambientIntensity
  }

  LaunchedEffect(sunIntensity) {
    spatialSurfaceView.filamentEngine.sunIntensity = sunIntensity
  }

  // Toast listener
  LaunchedEffect(Unit) {
    viewModel.toastEvents.collect { message ->
      Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black)
  ) {
    // 1. Unified Google Filament + ARCore SurfaceView Canvas
    AndroidView(
      factory = { spatialSurfaceView },
      modifier = Modifier
        .fillMaxSize()
        .testTag("spatial_filament_canvas")
    )

    // 2. Diagnostics HUD Overlay (When enabled or in AR tracking)
    if (showDiagnostics || displayMode == DisplayMode.AR) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(top = 70.dp, start = 16.dp, end = 16.dp)
          .align(Alignment.TopCenter)
      ) {
        DiagnosticsHud(telemetry = telemetry)
      }
    }

    // 3. Shutter Snapshot Flash Overlay
    if (flashAnim.value > 0.01f) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.White.copy(alpha = flashAnim.value))
      )
    }

    // 4. TOP CONTROLS: Mode Switcher Pill with Quick Access buttons
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(top = 12.dp, start = 16.dp, end = 16.dp)
        .align(Alignment.TopCenter)
    ) {
      // Settings Button
      IconButton(
        onClick = {
          hapticManager.performClick()
          viewModel.setShowSettings(true)
        },
        modifier = Modifier
          .clip(CircleShape)
          .background(Color(0xFF0F172A).copy(alpha = 0.85f))
          .testTag("settings_button")
      ) {
        Icon(
          imageVector = Icons.Default.Settings,
          contentDescription = "Settings",
          tint = Color.White
        )
      }

      TopModePill(
        currentMode = displayMode,
        onModeSelected = { newMode ->
          hapticManager.performHeavy()
          viewModel.setDisplayMode(newMode)
        }
      )

      // Model Selector Button
      IconButton(
        onClick = {
          hapticManager.performClick()
          viewModel.setShowModelSelector(true)
        },
        modifier = Modifier
          .clip(CircleShape)
          .background(Color(0xFF0F172A).copy(alpha = 0.85f))
          .testTag("models_button")
      ) {
        Icon(
          imageVector = Icons.Default.ViewInAr,
          contentDescription = "Models",
          tint = Color(0xFF38BDF8)
        )
      }
    }

    // 5. Animation Controls (Floating above bottom pill)
    if (displayMode == DisplayMode.OBJECT && (selectedModel?.hasAnimations == true)) {
      Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
          .fillMaxWidth()
          .align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .padding(bottom = 100.dp, start = 20.dp, end = 20.dp)
      ) {
        AnimationControlBar(
          isPlaying = isPlayingAnimation,
          currentTimeSec = 0f,
          durationSec = selectedModel?.animationDurationSec ?: 4.0f,
          speed = animationSpeed,
          onPlayPauseToggle = {
            hapticManager.performClick()
            viewModel.toggleAnimationPlay()
          },
          onSpeedChange = { speed ->
            viewModel.setAnimationSpeed(speed)
          },
          onScrubTime = { /* scrub time */ }
        )
      }
    }

    // 6. BOTTOM CONTROLS: Floating Action Pill [ PHOTO | (● REC) | Open | Clear ]
    Box(
      contentAlignment = Alignment.BottomCenter,
      modifier = Modifier
        .fillMaxWidth()
        .align(Alignment.BottomCenter)
        .navigationBarsPadding()
        .padding(bottom = 24.dp)
    ) {
      BottomActionPill(
        isRecording = isRecording,
        recordingDurationSec = recordingDurationSec,
        onPhotoClick = {
          hapticManager.performDouble()
          scope.launch {
            flashAnim.snapTo(0.85f)
            flashAnim.animateTo(0f, tween(350))
          }
          viewModel.saveSnapshot(
            android.graphics.Bitmap.createBitmap(1080, 1920, android.graphics.Bitmap.Config.ARGB_8888),
            context
          )
        },
        onRecClick = {
          hapticManager.performHeavy()
          viewModel.toggleRecording()
        },
        onOpenClick = {
          hapticManager.performClick()
          filePickerLauncher.launch("*/*")
        },
        onClearClick = {
          hapticManager.performHeavy()
          spatialSurfaceView.resetView()
          viewModel.resetOrRestoreModel()
        }
      )
    }

    // 7. Sheets for Model Selector & Settings
    if (showModelSelector) {
      ModelSelectorSheet(
        sheetState = modelSheetState,
        models = modelsList,
        selectedModel = selectedModel,
        onSelectModel = { model ->
          hapticManager.performClick()
          viewModel.selectModel(model)
          viewModel.setShowModelSelector(false)
        },
        onPickCustomFile = {
          viewModel.setShowModelSelector(false)
          filePickerLauncher.launch("*/*")
        },
        onDismiss = { viewModel.setShowModelSelector(false) }
      )
    }

    if (showSettings) {
      SettingsSheet(
        sheetState = settingsSheetState,
        ambientIntensity = ambientIntensity,
        onAmbientChange = { viewModel.setAmbientIntensity(it) },
        sunIntensity = sunIntensity,
        onSunChange = { viewModel.setSunIntensity(it) },
        showGridFloor = showGridFloor,
        onGridFloorChange = { viewModel.setShowGridFloor(it) },
        autoRotate = autoRotate,
        onAutoRotateChange = { viewModel.setAutoRotate(it) },
        ipdMm = ipdMm,
        onIpdChange = { viewModel.setIpdMm(it) },
        showDiagnostics = showDiagnostics,
        onDiagnosticsChange = { viewModel.setShowDiagnostics(it) },
        onResetScene = {
          spatialSurfaceView.resetView()
          viewModel.resetOrRestoreModel()
          viewModel.setShowSettings(false)
        },
        onDismiss = { viewModel.setShowSettings(false) }
      )
    }
  }
}
