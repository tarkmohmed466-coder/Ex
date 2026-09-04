package com.example.renderer

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import com.example.model.DisplayMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * High-performance Dual-Viewport Stereoscopic Camera SurfaceView.
 * In MR Mode: Duplicates the real-time camera stream into Left Eye and Right Eye viewports.
 * In AR Mode: Renders single full-screen hardware camera feed.
 * In Object Mode: Renders clean dark studio background.
 */
class DualCameraGLSurfaceView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

  companion object {
    private const val TAG = "DualCameraGLView"

    private const val VERTEX_SHADER = """
      attribute vec4 aPosition;
      attribute vec4 aTexCoord;
      uniform mat4 uTexMatrix;
      varying vec2 vTexCoord;
      void main() {
        gl_Position = aPosition;
        vTexCoord = (uTexMatrix * aTexCoord).xy;
      }
    """

    private const val FRAGMENT_SHADER = """
      #extension GL_OES_EGL_image_external : require
      precision mediump float;
      varying vec2 vTexCoord;
      uniform samplerExternalOES uTexture;
      void main() {
        gl_FragColor = texture2D(uTexture, vTexCoord);
      }
    """
  }

  private var program = 0
  private var aPositionHandle = 0
  private var aTexCoordHandle = 0
  private var uTexMatrixHandle = 0
  private var uTextureHandle = 0
  private var textureId = 0

  private var surfaceTexture: SurfaceTexture? = null
  private var cameraSurface: Surface? = null
  private var pendingRequest: SurfaceRequest? = null
  private var executor: Executor? = null

  private var viewWidth = 1
  private var viewHeight = 1
  private val texMatrix = FloatArray(16)
  private var hasNewFrame = false

  var displayMode: DisplayMode = DisplayMode.OBJECT
    set(value) {
      field = value
      try {
        requestRender()
      } catch (e: Exception) {
        // Safe when paused or detached
      }
    }

  private val vertexBuffer: FloatBuffer
  private val texBuffer: FloatBuffer

  init {
    setEGLContextClientVersion(2)
    setRenderer(this)
    renderMode = RENDERMODE_WHEN_DIRTY

    val quadCoords = floatArrayOf(
      -1.0f, -1.0f,
       1.0f, -1.0f,
      -1.0f,  1.0f,
       1.0f,  1.0f
    )
    val texCoords = floatArrayOf(
      0.0f, 0.0f,
      1.0f, 0.0f,
      0.0f, 1.0f,
      1.0f, 1.0f
    )

    vertexBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer()
      .apply {
        put(quadCoords)
        position(0)
      }

    texBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer()
      .apply {
        put(texCoords)
        position(0)
      }
  }

  fun provideSurface(request: SurfaceRequest, exec: Executor) {
    try {
      queueEvent {
        pendingRequest = request
        executor = exec
        val st = surfaceTexture
        if (st != null) {
          attachCameraRequest(request, exec, st)
        }
      }
      requestRender()
    } catch (e: Exception) {
      Log.w(TAG, "Failed queueEvent for provideSurface: ${e.message}")
    }
  }

  fun detachCamera() {
    try {
      queueEvent {
        pendingRequest = null
        cameraSurface?.release()
        cameraSurface = null
        hasNewFrame = false
      }
      requestRender()
    } catch (e: Exception) {
      Log.w(TAG, "detachCamera error: ${e.message}")
    }
  }

  private fun attachCameraRequest(request: SurfaceRequest, exec: Executor, st: SurfaceTexture) {
    try {
      cameraSurface?.release()
      cameraSurface = null
      st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
      val surface = Surface(st)
      cameraSurface = surface
      request.provideSurface(surface, exec) {
        try {
          surface.release()
        } catch (e: Exception) {
          Log.w(TAG, "Error releasing camera surface: ${e.message}")
        }
        if (cameraSurface == surface) {
          cameraSurface = null
        }
      }
      Log.i(TAG, "Camera surface attached: ${request.resolution.width}x${request.resolution.height}")
      requestRender()
    } catch (e: Exception) {
      Log.e(TAG, "Failed attaching camera surface: ${e.message}", e)
    }
  }

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    try {
      if (textureId != 0) {
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        textureId = 0
      }
      if (program != 0) {
        GLES20.glDeleteProgram(program)
        program = 0
      }

      program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
      aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
      aTexCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
      uTexMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
      uTextureHandle = GLES20.glGetUniformLocation(program, "uTexture")

      val textures = IntArray(1)
      GLES20.glGenTextures(1, textures, 0)
      textureId = textures[0]

      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

      val st = SurfaceTexture(textureId)
      st.setOnFrameAvailableListener(this)
      surfaceTexture = st

      val req = pendingRequest
      val exec = executor
      if (req != null && exec != null) {
        attachCameraRequest(req, exec, st)
        pendingRequest = null
      }
      requestRender()
    } catch (e: Exception) {
      Log.e(TAG, "Error in onSurfaceCreated: ${e.message}", e)
    }
  }

  override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
    viewWidth = maxOf(width, 1)
    viewHeight = maxOf(height, 1)
  }

  override fun onFrameAvailable(st: SurfaceTexture?) {
    hasNewFrame = true
    try {
      requestRender()
    } catch (e: Exception) {
      // Safe when view is pausing or destroyed
    }
  }

  override fun onDrawFrame(gl: GL10?) {
    try {
      GLES20.glClearColor(0.043f, 0.059f, 0.098f, 1.0f)
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

      if (displayMode == DisplayMode.OBJECT) return

      val st = surfaceTexture ?: return
      if (hasNewFrame) {
        try {
          st.updateTexImage()
          st.getTransformMatrix(texMatrix)
        } catch (e: Exception) {
          Log.w(TAG, "updateTexImage skipped: ${e.message}")
        }
        hasNewFrame = false
      }

      if (program == 0) return

      GLES20.glUseProgram(program)
      GLES20.glUniformMatrix4fv(uTexMatrixHandle, 1, false, texMatrix, 0)
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
      GLES20.glUniform1i(uTextureHandle, 0)

      GLES20.glEnableVertexAttribArray(aPositionHandle)
      GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

      GLES20.glEnableVertexAttribArray(aTexCoordHandle)
      GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

      if (displayMode == DisplayMode.MR) {
        val halfWidth = maxOf(viewWidth / 2, 1)
        // 1. Left Eye (Left Camera Viewport)
        GLES20.glViewport(0, 0, halfWidth, viewHeight)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 2. Right Eye (Right Camera Viewport)
        GLES20.glViewport(halfWidth, 0, halfWidth, viewHeight)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
      } else {
        // Single Camera Viewport (AR Mode)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
      }

      GLES20.glDisableVertexAttribArray(aPositionHandle)
      GLES20.glDisableVertexAttribArray(aTexCoordHandle)
    } catch (e: Exception) {
      Log.w(TAG, "Error in onDrawFrame: ${e.message}")
    }
  }

  private fun createProgram(vertexSource: String, fragmentSource: String): Int {
    val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    if (vertexShader == 0) return 0
    val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    if (fragmentShader == 0) return 0

    val prog = GLES20.glCreateProgram()
    if (prog != 0) {
      GLES20.glAttachShader(prog, vertexShader)
      GLES20.glAttachShader(prog, fragmentShader)
      GLES20.glLinkProgram(prog)
      val linkStatus = IntArray(1)
      GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
      if (linkStatus[0] != GLES20.GL_TRUE) {
        Log.e(TAG, "Failed linking shader program: " + GLES20.glGetProgramInfoLog(prog))
        GLES20.glDeleteProgram(prog)
        return 0
      }
    }
    return prog
  }

  private fun loadShader(type: Int, shaderCode: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, shaderCode)
    GLES20.glCompileShader(shader)
    val compiled = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
    if (compiled[0] == 0) {
      Log.e(TAG, "Could not compile shader $type: " + GLES20.glGetShaderInfoLog(shader))
      GLES20.glDeleteShader(shader)
      return 0
    }
    return shader
  }

  override fun onPause() {
    try {
      super.onPause()
    } catch (e: Exception) {
      Log.w(TAG, "Error pausing GLSurfaceView: ${e.message}")
    }
  }

  override fun onResume() {
    try {
      super.onResume()
    } catch (e: Exception) {
      Log.w(TAG, "Error resuming GLSurfaceView: ${e.message}")
    }
  }

  fun release() {
    try {
      queueEvent {
        try {
          surfaceTexture?.release()
          surfaceTexture = null
          cameraSurface?.release()
          cameraSurface = null
          if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
          }
          if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
          }
        } catch (e: Exception) {
          Log.w(TAG, "Error releasing GL resources: ${e.message}")
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "queueEvent failed on release: ${e.message}")
    }
  }
}
