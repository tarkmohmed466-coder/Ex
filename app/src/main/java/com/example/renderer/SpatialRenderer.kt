package com.example.renderer

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import com.example.model.DisplayMode
import com.example.model.SpatialAnchor
import com.example.model.SpatialMesh
import com.example.model.SpatialModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class SpatialRenderer(
  private val onTelemetryUpdate: (fps: Float, drawCalls: Int, vertexCount: Int) -> Unit = { _, _, _ -> }
) : GLSurfaceView.Renderer {

  // Active Scene State
  var currentModel: SpatialModel? = null
  var displayMode: DisplayMode = DisplayMode.OBJECT
  var showGridFloor: Boolean = true
  var autoRotate: Boolean = false
  var isPlayingAnimation: Boolean = true
  var animationSpeed: Float = 1.0f
  var animationTimeSec: Float = 0.0f
  var ambientLightIntensity: Float = 1.2f
  var sunIntensity: Float = 1.0f
  var stereoscopicIpd: Float = 0.064f // standard 64mm IPD
  var arAnchors: List<SpatialAnchor> = emptyList()

  // User Interactive Transforms
  var rotX: Float = 15.0f
  var rotY: Float = -25.0f
  var scale: Float = 1.0f
  var panX: Float = 0.0f
  var panY: Float = 0.0f

  // Viewport dimensions
  private var surfaceWidth: Int = 1080
  private var surfaceHeight: Int = 2340

  // Shader Program & Uniform Locations
  private var programId: Int = 0
  private var uMVPMatrixLoc: Int = -1
  private var uModelMatrixLoc: Int = -1
  private var uNormalMatrixLoc: Int = -1
  private var uViewPosLoc: Int = -1
  private var uLightPosLoc: Int = -1
  private var uLightColorLoc: Int = -1
  private var uBaseColorLoc: Int = -1
  private var uMetallicLoc: Int = -1
  private var uRoughnessLoc: Int = -1
  private var uEmissiveLoc: Int = -1
  private var uAmbientIntensityLoc: Int = -1
  private var uIsGridLoc: Int = -1

  // Matrices
  private val projectionMatrix = FloatArray(16)
  private val viewMatrix = FloatArray(16)
  private val modelMatrix = FloatArray(16)
  private val mvpMatrix = FloatArray(16)
  private val normalMatrix = FloatArray(16)
  private val tempMatrix = FloatArray(16)

  // Spatial Grid Plane Mesh
  private lateinit var gridMesh: SpatialMesh

  // Snapshot capture callback
  private var pendingSnapshotCallback: ((Bitmap) -> Unit)? = null

  // Performance Telemetry tracking
  private var frameCount = 0
  private var lastFpsTimestamp = SystemClock.elapsedRealtime()
  private var lastFrameTime = SystemClock.elapsedRealtime()

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthFunc(GLES30.GL_LEQUAL)
    GLES30.glEnable(GLES30.GL_BLEND)
    GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    GLES30.glEnable(GLES30.GL_CULL_FACE)
    GLES30.glCullFace(GLES30.GL_BACK)

    programId = SpatialShader.createProgram()
    if (programId != 0) {
      uMVPMatrixLoc = GLES30.glGetUniformLocation(programId, "uMVPMatrix")
      uModelMatrixLoc = GLES30.glGetUniformLocation(programId, "uModelMatrix")
      uNormalMatrixLoc = GLES30.glGetUniformLocation(programId, "uNormalMatrix")
      uViewPosLoc = GLES30.glGetUniformLocation(programId, "uViewPos")
      uLightPosLoc = GLES30.glGetUniformLocation(programId, "uLightPos")
      uLightColorLoc = GLES30.glGetUniformLocation(programId, "uLightColor")
      uBaseColorLoc = GLES30.glGetUniformLocation(programId, "uBaseColor")
      uMetallicLoc = GLES30.glGetUniformLocation(programId, "uMetallic")
      uRoughnessLoc = GLES30.glGetUniformLocation(programId, "uRoughness")
      uEmissiveLoc = GLES30.glGetUniformLocation(programId, "uEmissive")
      uAmbientIntensityLoc = GLES30.glGetUniformLocation(programId, "uAmbientIntensity")
      uIsGridLoc = GLES30.glGetUniformLocation(programId, "uIsGrid")
    }

    initGridMesh()
  }

  private fun initGridMesh() {
    val size = 5.0f
    val y = -1.2f
    val gridVerts = floatArrayOf(
      -size, y, -size,
       size, y, -size,
       size, y,  size,
      -size, y,  size
    )
    val gridNorms = floatArrayOf(
      0f, 1f, 0f,
      0f, 1f, 0f,
      0f, 1f, 0f,
      0f, 1f, 0f
    )
    val gridIndices = shortArrayOf(0, 1, 2, 0, 2, 3)

    val vBuf = ByteBuffer.allocateDirect(gridVerts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
      put(gridVerts); position(0)
    }
    val nBuf = ByteBuffer.allocateDirect(gridNorms.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
      put(gridNorms); position(0)
    }
    val iBuf = ByteBuffer.allocateDirect(gridIndices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
      put(gridIndices); position(0)
    }

    gridMesh = SpatialMesh(
      name = "grid_floor",
      vertexBuffer = vBuf,
      normalBuffer = nBuf,
      uvBuffer = null,
      colorBuffer = null,
      indexBuffer = iBuf,
      indexCount = gridIndices.size
    )
  }

  override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
    surfaceWidth = width
    surfaceHeight = height
    GLES30.glViewport(0, 0, width, height)
  }

  override fun onDrawFrame(gl: GL10?) {
    val currentTime = SystemClock.elapsedRealtime()
    val deltaTime = (currentTime - lastFrameTime) / 1000.0f
    lastFrameTime = currentTime

    // Animation time progress
    if (isPlayingAnimation) {
      animationTimeSec += deltaTime * animationSpeed
      val duration = currentModel?.animationDurationSec ?: 4.0f
      if (animationTimeSec > duration) {
        animationTimeSec %= duration
      }
    }

    if (autoRotate) {
      rotY += 24.0f * deltaTime
      if (rotY > 360f) rotY -= 360f
    }

    // Set background clear color
    when (displayMode) {
      DisplayMode.AR -> {
        // Transparent clear for Camera Passthrough
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
      }
      DisplayMode.MR -> {
        // Spatial MR background
        GLES30.glClearColor(0.02f, 0.03f, 0.06f, 1.0f)
      }
      DisplayMode.OBJECT -> {
        // Pure sleek dark OLED canvas
        GLES30.glClearColor(0.01f, 0.015f, 0.03f, 1.0f)
      }
    }
    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

    if (programId == 0) return
    GLES30.glUseProgram(programId)

    // Set global light uniforms
    GLES30.glUniform3f(uLightPosLoc, 3.0f, 6.0f, 4.0f)
    GLES30.glUniform3f(uLightColorLoc, sunIntensity, sunIntensity * 0.98f, sunIntensity * 0.95f)
    GLES30.glUniform1f(uAmbientIntensityLoc, ambientLightIntensity)

    var totalDrawCalls = 0
    var totalVertices = 0

    when (displayMode) {
      DisplayMode.OBJECT -> {
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        val aspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 45.0f, aspect, 0.1f, 100.0f)
        Matrix.setLookAtM(viewMatrix, 0, 0.0f, 0.0f, 4.2f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f)
        GLES30.glUniform3f(uViewPosLoc, 0.0f, 0.0f, 4.2f)

        // Draw Spatial Grid Floor
        if (showGridFloor) {
          drawGridFloor()
          totalDrawCalls++
        }

        // Draw active 3D Model
        currentModel?.let { model ->
          val calls = drawModelInstance(
            model = model,
            posX = panX,
            posY = panY,
            posZ = 0.0f,
            rotationX = rotX,
            rotationY = rotY,
            scaleFactor = scale
          )
          totalDrawCalls += calls
          totalVertices += model.vertexCount
        }
      }

      DisplayMode.AR -> {
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        val aspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 52.0f, aspect, 0.1f, 100.0f)
        Matrix.setLookAtM(viewMatrix, 0, 0.0f, 1.2f, 3.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f)
        GLES30.glUniform3f(uViewPosLoc, 0.0f, 1.2f, 3.0f)

        if (showGridFloor) {
          drawGridFloor()
          totalDrawCalls++
        }

        // Draw placed anchors or primary model
        if (arAnchors.isNotEmpty()) {
          arAnchors.forEach { anchor ->
            currentModel?.let { model ->
              val calls = drawModelInstance(
                model = model,
                posX = anchor.posX,
                posY = anchor.posY,
                posZ = anchor.posZ,
                rotationX = 0.0f,
                rotationY = anchor.rotY + rotY,
                scaleFactor = anchor.scale * scale
              )
              totalDrawCalls += calls
              totalVertices += model.vertexCount
            }
          }
        } else {
          currentModel?.let { model ->
            val calls = drawModelInstance(
              model = model,
              posX = panX,
              posY = panY,
              posZ = 0.0f,
              rotationX = rotX,
              rotationY = rotY,
              scaleFactor = scale
            )
            totalDrawCalls += calls
            totalVertices += model.vertexCount
          }
        }
      }

      DisplayMode.MR -> {
        // Real Stereoscopic Dual Viewport (Left Eye + Right Eye)
        val halfW = surfaceWidth / 2
        val aspect = halfW.toFloat() / surfaceHeight.toFloat()
        val ipdHalf = stereoscopicIpd / 2.0f

        // 1. LEFT EYE VIEWPORT
        GLES30.glViewport(0, 0, halfW, surfaceHeight)
        Matrix.perspectiveM(projectionMatrix, 0, 50.0f, aspect, 0.1f, 100.0f)
        Matrix.setLookAtM(viewMatrix, 0, -ipdHalf, 0.0f, 4.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f)
        GLES30.glUniform3f(uViewPosLoc, -ipdHalf, 0.0f, 4.0f)

        if (showGridFloor) {
          drawGridFloor()
          totalDrawCalls++
        }
        currentModel?.let { model ->
          val calls = drawModelInstance(
            model = model,
            posX = panX,
            posY = panY,
            posZ = 0.0f,
            rotationX = rotX,
            rotationY = rotY,
            scaleFactor = scale
          )
          totalDrawCalls += calls
          totalVertices += model.vertexCount
        }

        // 2. RIGHT EYE VIEWPORT
        GLES30.glViewport(halfW, 0, halfW, surfaceHeight)
        Matrix.perspectiveM(projectionMatrix, 0, 50.0f, aspect, 0.1f, 100.0f)
        Matrix.setLookAtM(viewMatrix, 0, ipdHalf, 0.0f, 4.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f)
        GLES30.glUniform3f(uViewPosLoc, ipdHalf, 0.0f, 4.0f)

        if (showGridFloor) {
          drawGridFloor()
          totalDrawCalls++
        }
        currentModel?.let { model ->
          val calls = drawModelInstance(
            model = model,
            posX = panX,
            posY = panY,
            posZ = 0.0f,
            rotationX = rotX,
            rotationY = rotY,
            scaleFactor = scale
          )
          totalDrawCalls += calls
          totalVertices += model.vertexCount
        }
      }
    }

    // Telemetry FPS computation
    frameCount++
    if (currentTime - lastFpsTimestamp >= 500) {
      val fps = (frameCount * 1000.0f) / (currentTime - lastFpsTimestamp)
      frameCount = 0
      lastFpsTimestamp = currentTime
      onTelemetryUpdate(fps, totalDrawCalls, totalVertices)
    }

    // Snapshot reading if requested
    pendingSnapshotCallback?.let { callback ->
      val bitmap = readPixelsToBitmap(surfaceWidth, surfaceHeight)
      pendingSnapshotCallback = null
      callback(bitmap)
    }
  }

  private fun drawModelInstance(
    model: SpatialModel,
    posX: Float,
    posY: Float,
    posZ: Float,
    rotationX: Float,
    rotationY: Float,
    scaleFactor: Float
  ): Int {
    // Model Matrix with animation float wobble
    Matrix.setIdentityM(modelMatrix, 0)
    Matrix.translateM(modelMatrix, 0, posX, posY, posZ)

    // Animation hover/wobble
    if (isPlayingAnimation) {
      val floatWobble = sin(animationTimeSec * 2.5f) * 0.08f
      Matrix.translateM(modelMatrix, 0, 0.0f, floatWobble, 0.0f)
    }

    Matrix.rotateM(modelMatrix, 0, rotationX, 1.0f, 0.0f, 0.0f)
    Matrix.rotateM(modelMatrix, 0, rotationY, 0.0f, 1.0f, 0.0f)

    if (isPlayingAnimation && model.hasAnimations) {
      val animRot = sin(animationTimeSec * 1.8f) * 4.0f
      Matrix.rotateM(modelMatrix, 0, animRot, 0.0f, 0.0f, 1.0f)
    }

    Matrix.scaleM(modelMatrix, 0, scaleFactor, scaleFactor, scaleFactor)

    // Normal matrix
    Matrix.invertM(tempMatrix, 0, modelMatrix, 0)
    Matrix.transposeM(normalMatrix, 0, tempMatrix, 0)

    // MVP matrix
    Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
    Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

    GLES30.glUniformMatrix4fv(uModelMatrixLoc, 1, false, modelMatrix, 0)
    GLES30.glUniformMatrix4fv(uNormalMatrixLoc, 1, false, normalMatrix, 0)
    GLES30.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)
    GLES30.glUniform1i(uIsGridLoc, 0)

    var calls = 0
    for (mesh in model.meshes) {
      val mat = mesh.material
      GLES30.glUniform3f(uBaseColorLoc, mat.baseColorR, mat.baseColorG, mat.baseColorB)
      GLES30.glUniform1f(uMetallicLoc, mat.metallic)
      GLES30.glUniform1f(uRoughnessLoc, mat.roughness)
      GLES30.glUniform3f(
        uEmissiveLoc,
        mat.emissiveR * mat.emissiveIntensity,
        mat.emissiveG * mat.emissiveIntensity,
        mat.emissiveB * mat.emissiveIntensity
      )

      GLES30.glEnableVertexAttribArray(0)
      GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, mesh.vertexBuffer)

      mesh.normalBuffer?.let { nBuf ->
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 0, nBuf)
      }

      val mode = if (mat.isWireframe) GLES30.GL_LINES else GLES30.GL_TRIANGLES
      GLES30.glDrawElements(mode, mesh.indexCount, GLES30.GL_UNSIGNED_SHORT, mesh.indexBuffer)

      GLES30.glDisableVertexAttribArray(0)
      if (mesh.normalBuffer != null) GLES30.glDisableVertexAttribArray(1)
      calls++
    }

    return calls
  }

  private fun drawGridFloor() {
    Matrix.setIdentityM(modelMatrix, 0)
    Matrix.invertM(tempMatrix, 0, modelMatrix, 0)
    Matrix.transposeM(normalMatrix, 0, tempMatrix, 0)
    Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
    Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

    GLES30.glUniformMatrix4fv(uModelMatrixLoc, 1, false, modelMatrix, 0)
    GLES30.glUniformMatrix4fv(uNormalMatrixLoc, 1, false, normalMatrix, 0)
    GLES30.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)
    GLES30.glUniform1i(uIsGridLoc, 1)

    GLES30.glEnableVertexAttribArray(0)
    GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, gridMesh.vertexBuffer)
    gridMesh.normalBuffer?.let {
      GLES30.glEnableVertexAttribArray(1)
      GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 0, it)
    }

    GLES30.glDrawElements(GLES30.GL_TRIANGLES, gridMesh.indexCount, GLES30.GL_UNSIGNED_SHORT, gridMesh.indexBuffer)

    GLES30.glDisableVertexAttribArray(0)
    GLES30.glDisableVertexAttribArray(1)
  }

  fun requestSnapshot(callback: (Bitmap) -> Unit) {
    pendingSnapshotCallback = callback
  }

  private fun readPixelsToBitmap(width: Int, height: Int): Bitmap {
    val pixelBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixelBuffer)

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    pixelBuffer.position(0)
    bitmap.copyPixelsFromBuffer(pixelBuffer)

    // Flip horizontally/vertically as GL coordinates are bottom-up
    val matrix = android.graphics.Matrix()
    matrix.preScale(1.0f, -1.0f)
    return Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, false)
  }

  fun resetTransform() {
    rotX = 15.0f
    rotY = -25.0f
    scale = 1.0f
    panX = 0.0f
    panY = 0.0f
  }
}
