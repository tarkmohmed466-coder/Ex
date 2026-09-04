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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.arcore.ExhibitSource
import com.example.engine.HapticManager
import com.example.model.DisplayMode
import com.example.renderer.SpatialSurfaceView
import com.example.ui.components.AnimationControlBar
import com.example.ui.components.BottomActionPill
import com.example.ui.components.CameraPassthroughView
import com.example.ui.components.DiagnosticsHud
import com.example.ui.components.ExhibitMarkerGuideSheet
import com.example.ui.components.ModelSelectorSheet
import com.example.ui.components.NearbyExhibitOverlay
import com.example.ui.components.SettingsSheet
import com.example.ui.components.StereoCameraOverlay
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
  val showMarkerGuide by viewModel.showMarkerGuide.collectAsState()
  val showDiagnostics by viewModel.showDiagnostics.collectAsState()
  val showGridFloor by viewModel.showGridFloor.collectAsState()
  val autoRotate by viewModel.autoRotate.collectAsState()
  val isPlayingAnimation by viewModel.isPlayingAnimation.collectAsState()
  val animationSpeed by viewModel.animationSpeed.collectAsState()
  val currentAnimationTimeSec by viewModel.currentAnimationTimeSec.collectAsState()
  val selectedAnimationTrack by viewModel.selectedAnimationTrack.collectAsState()
  val ambientIntensity by viewModel.ambientIntensity.collectAsState()
  val sunIntensity by viewModel.sunIntensity.collectAsState()
  val ipdMm by viewModel.ipdMm.collectAsState()
  val telemetry by viewModel.telemetry.collectAsState()
  val nearbyExhibit by viewModel.nearbyExhibit.collectAsState()
  val arAnchors by viewModel.arAnchors.collectAsState()

  // Sheet states
  val modelSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val markerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
        val dims = "${String.format("%.2f", filamentEngine.modelPhysicalWidthMeters)}m x ${String.format("%.2f", filamentEngine.modelPhysicalHeightMeters)}m x ${String.format("%.2f", filamentEngine.modelPhysicalDepthMeters)}m"
        viewModel.updateTelemetryFromEngine(
          fps = fps,
          drawCalls = drawCalls,
          vertexCount = vertexCount,
          trackingData = trackingData,
          depthManager = depthOcclusionManager,
          modelDimensions = dims
        )
      }
      onAnchorPlaced = { anchor, hitPos, source, modelId, modelTitle ->
        viewModel.addPlacedAnchor(
          anchorId = "anchor_${anchor.hashCode()}",
          worldPos = hitPos,
          source = source,
          modelId = modelId,
          modelTitle = modelTitle
        )
      }
      onExhibitMarkerRecognized = { marker, pos ->
        hapticManager.performDouble()
      }
      onSurfaceViewCreated(this)
    }
  }

  // Synchronize state with Filament Engine & ARCore Session
  LaunchedEffect(activeGlbBuffer, selectedModel) {
    val buf = activeGlbBuffer
    val model = selectedModel
    if (buf != null && model != null) {
      spatialSurfaceView.currentSelectedModelId = model.id
      spatialSurfaceView.currentSelectedModelTitle = model.title
      spatialSurfaceView.loadGlbBuffer(buf, model.title)
    } else {
      spatialSurfaceView.clearModelAndScene()
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
    // 0. Live Hardware Camera Passthrough (AR & MR modes)
    CameraPassthroughView(
      displayMode = displayMode,
      hasCameraPermission = hasCameraPermission,
      onRequestPermission = {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
      },
      onDualCameraCreated = { dualView ->
        dualView.onCameraTextureReady = { texName ->
          spatialSurfaceView.arCoreSessionManager.setCameraTextureName(texName)
        }
        spatialSurfaceView.dualCameraGLSurfaceView = dualView
      },
      isArCoreActive = (displayMode == DisplayMode.AR || displayMode == DisplayMode.MR) && spatialSurfaceView.arCoreSessionManager.isSupported,
      modifier = Modifier.fillMaxSize()
    )

    // 1. Unified Google Filament + ARCore SurfaceView Canvas
    AndroidView(
      factory = { spatialSurfaceView },
      modifier = Modifier
        .fillMaxSize()
        .testTag("spatial_filament_canvas")
    )

    // 2. Diagnostics HUD Overlay (When explicitly enabled via settings)
    if (showDiagnostics) {
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

    // 3. Proximity & Walking Exhibit Overlay (in AR / MR Modes)
    if (displayMode == DisplayMode.AR || displayMode == DisplayMode.MR) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(top = if (showDiagnostics) 180.dp else 70.dp, start = 16.dp, end = 16.dp)
          .align(Alignment.TopCenter)
      ) {
        NearbyExhibitOverlay(
          nearbyExhibit = nearbyExhibit,
          activeAnchorsCount = arAnchors.size,
          walkingMeters = telemetry.walkingDisplacementMeters
        )
      }
    }

    // 4. Stereoscopic Dual Camera Viewport Overlay in MR Mode
    if (displayMode == DisplayMode.MR) {
      StereoCameraOverlay(ipdMm = 64f)
    }

    // 5. Empty State Hint (When Scene is Cleared)
    if (selectedModel == null && arAnchors.isEmpty()) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .align(Alignment.Center)
          .padding(horizontal = 32.dp)
          .testTag("empty_scene_card")
      ) {
        androidx.compose.material3.Surface(
          shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
          color = Color(0xFF0F172A).copy(alpha = 0.88f),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
          modifier = Modifier.padding(16.dp)
        ) {
          androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)
          ) {
            Icon(
              imageVector = Icons.Default.ViewInAr,
              contentDescription = null,
              tint = Color(0xFF38BDF8),
              modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            androidx.compose.material3.Text(
              text = "Scene is Empty",
              color = Color.White,
              fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
              fontSize = 17.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            androidx.compose.material3.Text(
              text = "Tap 'Open' below to load a 3D exhibit.",
              color = Color(0xFF94A3B8),
              fontSize = 13.sp,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
          }
        }
      }
    }

    // 6. Shutter Snapshot Flash Overlay
    if (flashAnim.value > 0.01f) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.White.copy(alpha = flashAnim.value))
      )
    }

    // 7. TOP CONTROLS: Mode Switcher Pill centered (MR | AR | Object)
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(top = 12.dp)
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

    // 8. BOTTOM CONTROLS: Floating Action Pill [ PHOTO | (● REC) | Open | Clear ]
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
          spatialSurfaceView.captureSnapshot(
            onCaptured = { bmp ->
              viewModel.saveSnapshot(bmp, context)
            },
            onError = { errMsg ->
              viewModel.log("SNAPSHOT_ERR", errMsg)
            }
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
          spatialSurfaceView.clearModelAndScene()
          viewModel.clearActiveModelAndScene()
        }
      )
    }

    // 8. Sheets for Model Selector, Marker Guide & Settings
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

    if (showMarkerGuide) {
      ExhibitMarkerGuideSheet(
        sheetState = markerSheetState,
        onSelectModel = { modelId ->
          hapticManager.performClick()
          val model = modelsList.firstOrNull { it.id == modelId }
          if (model != null) {
            viewModel.selectModel(model)
          }
          viewModel.setShowMarkerGuide(false)
        },
        onDismiss = { viewModel.setShowMarkerGuide(false) }
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
