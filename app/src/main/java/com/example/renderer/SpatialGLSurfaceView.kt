package com.example.renderer

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.abs

class SpatialGLSurfaceView(
  context: Context,
  val spatialRenderer: SpatialRenderer,
  private val onSingleTap: (x: Float, y: Float) -> Unit = { _, _ -> }
) : GLSurfaceView(context) {

  private var previousX = 0f
  private var previousY = 0f
  private var isTwoFingerPan = false
  private var initialPanX = 0f
  private var initialPanY = 0f

  private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
    override fun onScale(detector: ScaleGestureDetector): Boolean {
      spatialRenderer.scale *= detector.scaleFactor
      spatialRenderer.scale = spatialRenderer.scale.coerceIn(0.2f, 5.0f)
      requestRender()
      return true
    }
  })

  private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
      onSingleTap(e.x, e.y)
      return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
      spatialRenderer.resetTransform()
      requestRender()
      return true
    }
  })

  init {
    setEGLContextClientVersion(3)
    setZOrderMediaOverlay(true)
    holder.setFormat(PixelFormat.TRANSLUCENT)
    setRenderer(spatialRenderer)
    renderMode = RENDERMODE_CONTINUOUSLY
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    scaleDetector.onTouchEvent(event)
    gestureDetector.onTouchEvent(event)

    val pointerCount = event.pointerCount

    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        previousX = event.x
        previousY = event.y
        isTwoFingerPan = false
      }

      MotionEvent.ACTION_POINTER_DOWN -> {
        if (pointerCount == 2) {
          isTwoFingerPan = true
          initialPanX = (event.getX(0) + event.getX(1)) / 2f
          initialPanY = (event.getY(0) + event.getY(1)) / 2f
        }
      }

      MotionEvent.ACTION_MOVE -> {
        if (!scaleDetector.isInProgress) {
          if (pointerCount == 1 && !isTwoFingerPan) {
            // 3D Orbital Rotation
            val dx = event.x - previousX
            val dy = event.y - previousY

            spatialRenderer.rotY += dx * 0.45f
            spatialRenderer.rotX += dy * 0.45f
            spatialRenderer.rotX = spatialRenderer.rotX.coerceIn(-85.0f, 85.0f)
          } else if (pointerCount >= 2) {
            // Pan Movement
            val currentPanX = (event.getX(0) + event.getX(1)) / 2f
            val currentPanY = (event.getY(0) + event.getY(1)) / 2f

            val dx = currentPanX - initialPanX
            val dy = currentPanY - initialPanY

            spatialRenderer.panX += dx * 0.003f
            spatialRenderer.panY -= dy * 0.003f

            initialPanX = currentPanX
            initialPanY = currentPanY
          }
        }
        previousX = event.x
        previousY = event.y
      }

      MotionEvent.ACTION_POINTER_UP -> {
        if (pointerCount <= 2) {
          isTwoFingerPan = false
        }
      }

      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        isTwoFingerPan = false
      }
    }

    return true
  }
}
