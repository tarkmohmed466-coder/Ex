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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
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
import com.example.ui.components.BottomActionPill
import com.example.ui.components.CameraPreview
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
  val isRecording by viewModel.isRecording.collectAsState()
  val recordingDurationSec by viewModel.recordingDurationSec.collectAsState()
  val showGridFloor by viewModel.showGridFloor.collectAsState()
  val autoRotate by viewModel.autoRotate.collectAsState()
  val isPlayingAnimation by viewModel.isPlayingAnimation.collectAsState()
  val animationSpeed by viewModel.animationSpeed.collectAsState()
  val ambientIntensity by viewModel.ambientIntensity.collectAsState()
  val sunIntensity by viewModel.sunIntensity.collectAsState()
  val ipdMm by viewModel.ipdMm.collectAsState()
  val arAnchors by viewModel.arAnchors.collectAsState()


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
    if ((displayMode == DisplayMode.AR || displayMode == DisplayMode.MR) && hasCameraPermission == 0f) {
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

  // MR Gyroscope head tracking sensor (additive offset allowing full touch rotation & pan)
  DisposableEffect(displayMode) {
    val sensorsManager = SensorsManager(context) { pitch, roll, yaw ->
      if (displayMode == DisplayMode.MR) {
        spatialRenderer.sensorRotX = pitch * 0.15f
        spatialRenderer.sensorRotY = yaw * 0.15f
      }
    }
    if (displayMode == DisplayMode.MR) {
      sensorsManager.start()
    }
    onDispose {
      sensorsManager.stop()
      spatialRenderer.sensorRotX = 0f
      spatialRenderer.sensorRotY = 0f
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
          val hit = spatialRenderer.screenToPlaneHit(x, y)
          if (hit != null) {
            viewModel.placeArAnchorAt(hit[0], hit[1], hit[2])
          } else {
            viewModel.placeArAnchor(x / context.resources.displayMetrics.widthPixels, y / context.resources.displayMetrics.heightPixels)
          }
        }
      }
    )
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black)
  ) {
    // 1. AR / MR Camera Pass-through Layer
    if ((displayMode == DisplayMode.AR || displayMode == DisplayMode.MR) && hasCameraPermission > 0f) {
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

    // 4. TOP CONTROLS: Mode Switcher Pill [ MR | AR | Object ]
    Box(
      contentAlignment = Alignment.TopCenter,
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(top = 16.dp)
        .align(Alignment.TopCenter)
    ) {
      TopModePill(
        currentMode = displayMode,
        onModeSelected = { newMode ->
          hapticManager.performHeavy()
          viewModel.setDisplayMode(newMode)
        }
      )
    }

    // 5. BOTTOM CONTROLS: Bottom Floating Action Pill [ PHOTO | (● REC) | Open | Clear ]
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
          // Directly open system files picker
          filePickerLauncher.launch("*/*")
        },
        onClearClick = {
          hapticManager.performHeavy()
          spatialRenderer.resetTransform()
          viewModel.resetOrRestoreModel()
        }
      )
    }
  }
}

