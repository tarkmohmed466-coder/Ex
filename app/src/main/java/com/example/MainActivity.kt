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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.example.engine.SensorsManager
import com.example.model.DisplayMode
import com.example.renderer.SpatialGLSurfaceView
import com.example.renderer.SpatialRenderer
import com.example.ui.components.AnimationControlBar
import com.example.ui.components.BottomActionPill
import com.example.ui.components.CameraPreview
import com.example.ui.components.DiagnosticsHud
import com.example.ui.components.ModelSelectorSheet
import com.example.ui.components.SettingsSheet
import com.example.ui.components.TopModePill
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.SpatialViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  private val viewModel: SpatialViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      MyApplicationTheme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = Color(0xFF000000)
        ) {
          MixedRealityScreen(viewModel = viewModel)
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixedRealityScreen(
  viewModel: SpatialViewModel
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val hapticManager = remember { HapticManager(context) }

  // Observed View Model States
  val displayMode by viewModel.displayMode.collectAsState()
  val selectedModel by viewModel.selectedModel.collectAsState()
  val modelsList by viewModel.modelsList.collectAsState()
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
  val arAnchors by viewModel.arAnchors.collectAsState()

  // Sheet states
  val modelSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  // Camera Permission state for AR/MR passthrough
  var hasCameraPermission by remember {
    mutableFloatStateOf(
      if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) 1f else 0f
    )
  }

  val cameraPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { granted ->
    hasCameraPermission = if (granted) 1f else 0f
  }

  // GLB File Picker launcher
  val filePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
  ) { uri ->
    uri?.let { viewModel.loadCustomGlbFromUri(it, context) }
  }

  // Flash snapshot animation alpha
  val flashAnim = remember { Animatable(0f) }

  // Setup OpenGL Renderer
  val spatialRenderer = remember {
    SpatialRenderer(
      onTelemetryUpdate = { fps, drawCalls, vertexCount ->
        viewModel.updateTelemetry(fps, drawCalls, vertexCount)
      }
    )
  }

  // Synchronize state with renderer
  LaunchedEffect(selectedModel) {
    spatialRenderer.currentModel = selectedModel
  }
  LaunchedEffect(displayMode) {
    spatialRenderer.displayMode = displayMode
    if (displayMode == DisplayMode.AR && hasCameraPermission == 0f) {
      cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
  }
  LaunchedEffect(showGridFloor) {
    spatialRenderer.showGridFloor = showGridFloor
  }
  LaunchedEffect(autoRotate) {
    spatialRenderer.autoRotate = autoRotate
  }
  LaunchedEffect(isPlayingAnimation) {
    spatialRenderer.isPlayingAnimation = isPlayingAnimation
  }
  LaunchedEffect(animationSpeed) {
    spatialRenderer.animationSpeed = animationSpeed
  }
  LaunchedEffect(ambientIntensity) {
    spatialRenderer.ambientLightIntensity = ambientIntensity
  }
  LaunchedEffect(sunIntensity) {
    spatialRenderer.sunIntensity = sunIntensity
  }
  LaunchedEffect(ipdMm) {
    spatialRenderer.stereoscopicIpd = ipdMm / 1000.0f
  }
  LaunchedEffect(arAnchors) {
    spatialRenderer.arAnchors = arAnchors
  }

  // MR Gyroscope head tracking sensor
  DisposableEffect(displayMode) {
    val sensorsManager = SensorsManager(context) { pitch, roll, yaw ->
      if (displayMode == DisplayMode.MR) {
        spatialRenderer.rotX = pitch * 0.3f
        spatialRenderer.rotY = yaw * 0.3f
      }
    }
    if (displayMode == DisplayMode.MR) {
      sensorsManager.start()
    }
    onDispose {
      sensorsManager.stop()
    }
  }

  // Toast listener
  LaunchedEffect(Unit) {
    viewModel.toastEvents.collect { message ->
      Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
  }

  // Spatial GL Surface View holder
  val glSurfaceView = remember {
    SpatialGLSurfaceView(
      context = context,
      spatialRenderer = spatialRenderer,
      onSingleTap = { x, y ->
        if (displayMode == DisplayMode.AR) {
          hapticManager.performHeavy()
          viewModel.placeArAnchor(x / context.resources.displayMetrics.widthPixels, y / context.resources.displayMetrics.heightPixels)
        }
      }
    )
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black)
  ) {
    // 1. AR Camera Pass-through Layer
    if (displayMode == DisplayMode.AR && hasCameraPermission > 0f) {
      CameraPreview(modifier = Modifier.fillMaxSize())
    }

    // 2. 3D OpenGL ES 3.0 Rendering Canvas
    AndroidView(
      factory = { glSurfaceView },
      modifier = Modifier
        .fillMaxSize()
        .testTag("spatial_gl_canvas")
    )

    // 3. Shutter Snapshot Flash Overlay
    if (flashAnim.value > 0.01f) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.White.copy(alpha = flashAnim.value))
      )
    }

    // 4. TOP CONTROLS: Mode Switcher Pill & Quick Actions
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(top = 12.dp)
        .align(Alignment.TopCenter)
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Quick Settings Button (Left)
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xFF0F172A).copy(alpha = 0.65f))
            .clickable {
              hapticManager.performClick()
              viewModel.setShowSettings(true)
            }
            .testTag("quick_settings_button")
        ) {
          Icon(
            imageVector = Icons.Default.Tune,
            contentDescription = "Scene Settings",
            tint = Color(0xFF94A3B8),
            modifier = Modifier.size(22.dp)
          )
        }

        // Top Centered Segmented Mode Pill [ MR | AR | Object ]
        TopModePill(
          currentMode = displayMode,
          onModeSelected = { newMode ->
            hapticManager.performHeavy()
            viewModel.setDisplayMode(newMode)
          }
        )

        // Quick Diagnostics HUD Toggle (Right)
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
              if (showDiagnostics) Color(0xFF0284C7).copy(alpha = 0.85f)
              else Color(0xFF0F172A).copy(alpha = 0.65f)
            )
            .clickable {
              hapticManager.performClick()
              viewModel.setShowDiagnostics(!showDiagnostics)
            }
            .testTag("quick_diagnostics_button")
        ) {
          Icon(
            imageVector = Icons.Default.BarChart,
            contentDescription = "Diagnostics",
            tint = if (showDiagnostics) Color.White else Color(0xFF94A3B8),
            modifier = Modifier.size(22.dp)
          )
        }
      }

      // Diagnostics Telemetry Overlay
      AnimatedVisibility(
        visible = showDiagnostics,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically(),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
      ) {
        DiagnosticsHud(telemetry = telemetry, modifier = Modifier.fillMaxWidth())
      }
    }

    // 5. BOTTOM CONTROLS: Animation Scrubber + Bottom Floating Action Pill
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .fillMaxWidth()
        .align(Alignment.BottomCenter)
        .navigationBarsPadding()
        .padding(bottom = 18.dp)
    ) {
      // Animation Control Bar (if model has animations)
      if (selectedModel?.hasAnimations == true && displayMode == DisplayMode.OBJECT) {
        AnimationControlBar(
          isPlaying = isPlayingAnimation,
          currentTimeSec = spatialRenderer.animationTimeSec,
          durationSec = selectedModel?.animationDurationSec ?: 4.0f,
          speed = animationSpeed,
          onPlayPauseToggle = {
            hapticManager.performClick()
            viewModel.toggleAnimationPlay()
          },
          onSpeedChange = { newSpeed ->
            hapticManager.performClick()
            viewModel.setAnimationSpeed(newSpeed)
          },
          onScrubTime = { newTime ->
            spatialRenderer.animationTimeSec = newTime
          },
          modifier = Modifier
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .fillMaxWidth()
        )
        Spacer(modifier = Modifier.padding(top = 4.dp))
      }

      // Bottom Action Pill [ PHOTO | (● REC) | Open | Clear ]
      BottomActionPill(
        isRecording = isRecording,
        recordingDurationSec = recordingDurationSec,
        onPhotoClick = {
          hapticManager.performDouble()
          scope.launch {
            flashAnim.snapTo(0.85f)
            flashAnim.animateTo(0f, tween(350))
          }
          spatialRenderer.requestSnapshot { bitmap ->
            viewModel.saveSnapshot(bitmap, context)
          }
        },
        onRecClick = {
          hapticManager.performHeavy()
          viewModel.toggleRecording()
        },
        onOpenClick = {
          hapticManager.performClick()
          viewModel.setShowModelSelector(true)
        },
        onClearClick = {
          hapticManager.performHeavy()
          spatialRenderer.resetTransform()
          viewModel.clearScene()
        }
      )
    }

    // 6. BOTTOM SHEETS: Model Library & Scene Settings
    if (showModelSelector) {
      ModelSelectorSheet(
        sheetState = modelSheetState,
        models = modelsList,
        selectedModel = selectedModel,
        onSelectModel = { model ->
          hapticManager.performClick()
          viewModel.selectModel(model)
        },
        onPickCustomFile = {
          hapticManager.performClick()
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
          spatialRenderer.resetTransform()
          viewModel.clearScene()
        },
        onDismiss = { viewModel.setShowSettings(false) }
      )
    }
  }
}
