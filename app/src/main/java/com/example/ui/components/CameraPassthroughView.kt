package com.example.ui.components

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.model.DisplayMode
import com.example.renderer.DualCameraGLSurfaceView

/**
 * CameraPassthroughView provides real-time, hardware-accelerated camera feed.
 * - In MR Mode: Doubles the camera into Left and Right stereo viewports.
 * - In AR Mode: Renders single full-screen camera passthrough.
 * - In Object Mode: Clean dark studio canvas.
 */
@Composable
fun CameraPassthroughView(
  displayMode: DisplayMode,
  hasCameraPermission: Boolean,
  onRequestPermission: () -> Unit,
  onDualCameraCreated: ((DualCameraGLSurfaceView) -> Unit)? = null,
  isArCoreActive: Boolean = false,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  val isCameraRequired = (displayMode == DisplayMode.AR || displayMode == DisplayMode.MR) && hasCameraPermission

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color(0xFF0B0F19))
      .testTag("camerax_preview_view")
  ) {
    // 1. Dual Camera Viewport - active and mounted ONLY in AR/MR modes with permission granted
    if (isCameraRequired) {
      val dualCameraView = remember {
        DualCameraGLSurfaceView(context).also {
          onDualCameraCreated?.invoke(it)
        }
      }

      DisposableEffect(lifecycleOwner, dualCameraView) {
        val observer = LifecycleEventObserver { _, event ->
          when (event) {
            Lifecycle.Event.ON_RESUME -> dualCameraView.onResume()
            Lifecycle.Event.ON_PAUSE -> dualCameraView.onPause()
            Lifecycle.Event.ON_DESTROY -> dualCameraView.release()
            else -> {}
          }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
          lifecycleOwner.lifecycle.removeObserver(observer)
          dualCameraView.release()
        }
      }

      // Bind CameraX ONLY as fallback when ARCore is not actively running camera
      DisposableEffect(lifecycleOwner, displayMode, isArCoreActive, dualCameraView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        var isDisposed = false

        cameraProviderFuture.addListener({
          if (isDisposed) return@addListener
          try {
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()

            dualCameraView.displayMode = displayMode
            if (!isArCoreActive) {
              // CameraX Fallback when ARCore is unavailable
              val preview = Preview.Builder()
                .setTargetResolution(Size(1280, 720))
                .build()
                .also {
                  it.setSurfaceProvider(executor) { request ->
                    dualCameraView.provideSurface(request, executor)
                  }
                }

              val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
              if (cameraProvider.hasCamera(cameraSelector)) {
                cameraProvider.bindToLifecycle(
                  lifecycleOwner,
                  cameraSelector,
                  preview
                )
                Log.i("CameraPassthroughView", "CameraX bound with DualCameraGLSurfaceView as fallback")
              }
            } else {
              Log.i("CameraPassthroughView", "ARCore active: CameraX bypassed for zero temporal jitter synchronization")
            }
          } catch (e: Exception) {
            Log.e("CameraPassthroughView", "Camera binding error: ${e.message}", e)
          }
        }, executor)

        onDispose {
          isDisposed = true
          try {
            if (cameraProviderFuture.isDone) {
              cameraProviderFuture.get().unbindAll()
            }
          } catch (e: Exception) {
            Log.w("CameraPassthroughView", "Error unbinding camera on dispose: ${e.message}")
          }
          dualCameraView.detachCamera()
        }
      }

      AndroidView(
        factory = { dualCameraView },
        modifier = Modifier.fillMaxSize()
      )
    }

    // 2. Permission Request Card - shown in AR/MR modes when camera permission is missing
    if (!hasCameraPermission && displayMode != DisplayMode.OBJECT) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .fillMaxSize()
          .background(Color(0xFF0F172A))
          .padding(32.dp)
          .testTag("camera_permission_card")
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          Icon(
            imageVector = Icons.Default.CameraAlt,
            contentDescription = null,
            tint = Color(0xFF38BDF8),
            modifier = Modifier.size(56.dp)
          )
          Spacer(modifier = Modifier.height(16.dp))
          Text(
            text = "Camera Access Required",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "Enable camera permissions to view 3D exhibits placed in your physical environment.",
            color = Color(0xFF94A3B8),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
          )
          Spacer(modifier = Modifier.height(20.dp))
          Button(
            onClick = onRequestPermission,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.testTag("grant_camera_permission_button")
          ) {
            Text("Allow Camera", color = Color.White, fontWeight = FontWeight.SemiBold)
          }
        }
      }
    }
  }
}
