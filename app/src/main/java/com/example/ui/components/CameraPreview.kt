package com.example.ui.components

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

@Composable
fun CameraPreview(
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  val previewView = remember {
    PreviewView(context).apply {
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
      scaleType = PreviewView.ScaleType.FILL_CENTER
    }
  }

  AndroidView(
    factory = {
      val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
      cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build().also {
          it.surfaceProvider = previewView.surfaceProvider
        }
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
          cameraProvider.unbindAll()
          cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }, ContextCompat.getMainExecutor(context))
      previewView
    },
    modifier = modifier.fillMaxSize()
  )
}
