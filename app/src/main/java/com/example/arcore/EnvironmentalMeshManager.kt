package com.example.arcore

import android.media.Image
import android.util.Log
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.StreetscapeGeometry
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Surface category classification for reconstructed environmental polygons and meshes.
 */
enum class MeshSurfaceCategory {
  FLOOR,
  CEILING,
  WALL,
  TABLE_SURFACE,
  DESK_OR_COUNTER,
  OUTDOOR_TERRAIN,
  BUILDING_FACADE,
  GENERIC_OBSTACLE
}

/**
 * Source type of the spatial geometry to separate detected planes from 3D meshes.
 */
enum class GeometrySourceType {
  DETECTED_PLANE_2D_POLYGON,
  STREETSCAPE_3D_MESH,
  ENVIRONMENTAL_3D_MESH
}

/**
 * Geometric chunk representing real-world physical boundaries.
 */
data class MeshChunk(
  val id: String,
  val sourceType: GeometrySourceType,
  val category: MeshSurfaceCategory,
  val vertexCount: Int,
  val triangleCount: Int,
  val centerPosition: FloatArray,
  val surfaceAreaSquareMeters: Float,
  val vertexBuffer: FloatBuffer? = null,
  val indexBuffer: IntBuffer? = null
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as MeshChunk
    return id == other.id
  }
  override fun hashCode(): Int = id.hashCode()
}

/**
 * Telemetry tracking real 3D mesh geometry vs 2D convex plane boundaries.
 * Strictly separates:
 * 1. Plane Detection (2D convex hulls)
 * 2. Streetscape Geometry (Outdoor mesh)
 * 3. Local Environmental Mesh (Point cloud / volumetric mesh)
 * 4. Dense Local Reconstruction (High-density local mesh)
 * 5. Full Scene Reconstruction (Multi-surface volumetric coverage)
 */
data class ReconstructionTelemetry(
  val isReconstructionActive: Boolean = false,
  val isPlaneDetectionActive: Boolean = false,
  val isStreetscapeGeometryActive: Boolean = false,
  val isLocalEnvironmentalMeshActive: Boolean = false,
  val isDenseLocalReconstructionActive: Boolean = false,
  val isDenseLocalMeshActive: Boolean = isDenseLocalReconstructionActive,
  val isFull3dSceneReconstruction: Boolean = false,
  val hasReal3dMeshGeometry: Boolean = false,
  val detectedPlanesCount: Int = 0,
  val streetscapeGeometriesCount: Int = 0,
  val denseMeshChunksCount: Int = 0,
  val totalChunks: Int = 0,
  val totalVertices: Int = 0,
  val totalTriangles: Int = 0,
  val totalSurfaceAreaSqMeters: Float = 0f,
  val localMeshAreaSqMeters: Float = 0f,
  val hasFloorPlane: Boolean = false,
  val hasWallPlane: Boolean = false,
  val hasTableSurface: Boolean = false
)

/**
 * Production-Grade Environmental Mesh & Scene Reconstruction Manager.
 * Rigorously separates:
 * 1. Detected 2D Planes (Convex polygon hulls from plane detection).
 * 2. Outdoor Streetscape Geometry (Dense 3D meshes of buildings & terrain).
 * 3. Environmental 3D Meshes (Dense volumetric surface reconstruction).
 * 4. Semantic Surface Classification (Floor vs Table vs Wall vs Ceiling).
 *
 * NOTE: Does NOT classify every upward horizontal plane as floor. Distinguishes tables, desks, and floors.
 * Does NOT claim full 3D scene reconstruction unless actual 3D mesh geometry is available.
 */
class EnvironmentalMeshManager {

  companion object {
    private const val TAG = "EnvironmentalMeshManager"
    // Height threshold relative to camera eye-level (~1.4m): surfaces below -0.85m are floors
    private const val FLOOR_HEIGHT_THRESHOLD_METERS = -0.85f
  }

  var telemetry: ReconstructionTelemetry = ReconstructionTelemetry()
    private set

  private val detectedPlaneChunks = mutableMapOf<String, MeshChunk>()
  private val streetscapeChunks = mutableMapOf<String, MeshChunk>()
  private val environmental3dChunks = mutableMapOf<String, MeshChunk>()

  /**
   * Updates environmental mesh representation from current ARCore trackables.
   */
  fun updateEnvironmentalMesh(session: Session, frame: com.google.ar.core.Frame? = null) {
    try {
      var totalVerts = 0
      var totalTris = 0
      var totalArea = 0f
      var hasFloor = false
      var hasWall = false
      var hasTable = false

      detectedPlaneChunks.clear()
      streetscapeChunks.clear()

      // 1. Process physical plane geometry (2D convex polygon boundaries)
      val planes = session.getAllTrackables(Plane::class.java)
      for (plane in planes) {
        if (plane.trackingState != TrackingState.TRACKING) continue

        val polygon = plane.polygon ?: continue
        val numVertices = polygon.limit() / 2
        if (numVertices < 3) continue

        val centerPose = plane.centerPose
        val extentX = plane.extentX
        val extentZ = plane.extentZ
        val area = extentX * extentZ
        val triangles = numVertices - 2

        // Classify surface category accurately based on orientation and relative elevation
        val category = when (plane.type) {
          Plane.Type.HORIZONTAL_UPWARD_FACING -> {
            // Check plane elevation: low elevation is floor, elevated is table/desk
            if (centerPose.ty() <= FLOOR_HEIGHT_THRESHOLD_METERS) {
              hasFloor = true
              MeshSurfaceCategory.FLOOR
            } else {
              hasTable = true
              MeshSurfaceCategory.TABLE_SURFACE
            }
          }
          Plane.Type.HORIZONTAL_DOWNWARD_FACING -> MeshSurfaceCategory.CEILING
          Plane.Type.VERTICAL -> {
            hasWall = true
            MeshSurfaceCategory.WALL
          }
          else -> MeshSurfaceCategory.GENERIC_OBSTACLE
        }

        val chunk = MeshChunk(
          id = "plane_${plane.hashCode()}",
          sourceType = GeometrySourceType.DETECTED_PLANE_2D_POLYGON,
          category = category,
          vertexCount = numVertices,
          triangleCount = triangles,
          centerPosition = floatArrayOf(centerPose.tx(), centerPose.ty(), centerPose.tz()),
          surfaceAreaSquareMeters = area
        )
        detectedPlaneChunks[chunk.id] = chunk

        totalVerts += numVertices
        totalTris += triangles
        totalArea += area
      }

      // 2. Process outdoor Streetscape Geometry meshes if available (Real 3D Mesh Geometry)
      try {
        val streetscapes = session.getAllTrackables(StreetscapeGeometry::class.java)
        for (sg in streetscapes) {
          if (sg.trackingState != TrackingState.TRACKING) continue

          val mesh = sg.mesh
          val vertCount = mesh.vertexList.limit() / 3
          val triCount = mesh.indexList.limit() / 3
          val category = when (sg.type) {
            StreetscapeGeometry.Type.BUILDING -> MeshSurfaceCategory.BUILDING_FACADE
            StreetscapeGeometry.Type.TERRAIN -> MeshSurfaceCategory.OUTDOOR_TERRAIN
            else -> MeshSurfaceCategory.GENERIC_OBSTACLE
          }

          val pose = sg.meshPose

          // Compute real surface area from actual 3D mesh vertices and triangle indices
          var calculatedArea = 0f
          val vBuf = mesh.vertexList
          val iBuf = mesh.indexList
          val numTris = iBuf.limit() / 3
          for (t in 0 until minOf(numTris, 2000)) {
            val i0 = iBuf.get(t * 3).toInt() and 0xFFFF
            val i1 = iBuf.get(t * 3 + 1).toInt() and 0xFFFF
            val i2 = iBuf.get(t * 3 + 2).toInt() and 0xFFFF
            if (i0 * 3 + 2 < vBuf.limit() && i1 * 3 + 2 < vBuf.limit() && i2 * 3 + 2 < vBuf.limit()) {
              val ax = vBuf.get(i0 * 3); val ay = vBuf.get(i0 * 3 + 1); val az = vBuf.get(i0 * 3 + 2)
              val bx = vBuf.get(i1 * 3); val by = vBuf.get(i1 * 3 + 1); val bz = vBuf.get(i1 * 3 + 2)
              val cx = vBuf.get(i2 * 3); val cy = vBuf.get(i2 * 3 + 1); val cz = vBuf.get(i2 * 3 + 2)
              // Vector AB x Vector AC
              val abx = bx - ax; val aby = by - ay; val abz = bz - az
              val acx = cx - ax; val acy = cy - ay; val acz = cz - az
              val crossX = aby * acz - abz * acy
              val crossY = abz * acx - abx * acz
              val crossZ = abx * acy - aby * acx
              calculatedArea += 0.5f * Math.sqrt((crossX * crossX + crossY * crossY + crossZ * crossZ).toDouble()).toFloat()
            }
          }

          val chunk = MeshChunk(
            id = "streetscape_${sg.hashCode()}",
            sourceType = GeometrySourceType.STREETSCAPE_3D_MESH,
            category = category,
            vertexCount = vertCount,
            triangleCount = triCount,
            centerPosition = floatArrayOf(pose.tx(), pose.ty(), pose.tz()),
            surfaceAreaSquareMeters = calculatedArea
          )
          streetscapeChunks[chunk.id] = chunk

          totalVerts += vertCount
          totalTris += triCount
          totalArea += chunk.surfaceAreaSquareMeters
        }
      } catch (_: Throwable) {
        // Streetscape not active on current device/environment
      }

      // 3. Environmental 3D Mesh: Extract real physical 3D mesh geometry from ARCore 16-bit depth buffer
      environmental3dChunks.clear()
      if (frame != null && frame.camera.trackingState == TrackingState.TRACKING) {
        var depthImage: Image? = null
        try {
          depthImage = try {
            frame.acquireDepthImage16Bits()
          } catch (_: Throwable) {
            frame.acquireRawDepthImage16Bits()
          }

          if (depthImage != null) {
            val width = depthImage.width
            val height = depthImage.height
            val planes = depthImage.planes
            if (planes.isNotEmpty() && width > 0 && height > 0) {
              val plane = planes[0]
              val buffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
              val pixelStride = plane.pixelStride
              val rowStride = plane.rowStride

              // Intrinsic parameters for unprojection
              val intrinsics = frame.camera.imageIntrinsics
              val fx = intrinsics.focalLength[0] * (width.toFloat() / intrinsics.imageDimensions[0])
              val fy = intrinsics.focalLength[1] * (height.toFloat() / intrinsics.imageDimensions[1])
              val cx = intrinsics.principalPoint[0] * (width.toFloat() / intrinsics.imageDimensions[0])
              val cy = intrinsics.principalPoint[1] * (height.toFloat() / intrinsics.imageDimensions[1])

              val camPose = frame.camera.pose
              val step = 10 // grid step size for regular spatial mesh sampling
              val gridW = (width - 1) / step + 1
              val gridH = (height - 1) / step + 1

              // World positions grid: 3 floats per sample
              val gridPositions = FloatArray(gridW * gridH * 3)
              val gridValid = BooleanArray(gridW * gridH)

              for (gy in 0 until gridH) {
                val y = minOf(gy * step, height - 1)
                val rowStart = y * rowStride
                for (gx in 0 until gridW) {
                  val x = minOf(gx * step, width - 1)
                  val byteIdx = rowStart + x * pixelStride
                  val depthMm = buffer.getShort(byteIdx).toInt() and 0xFFFF
                  val idx = gy * gridW + gx
                  if (depthMm in 200..6000) {
                    val zM = depthMm / 1000f
                    val camX = (x - cx) * zM / fx
                    val camY = -(y - cy) * zM / fy
                    val camZ = -zM

                    val worldP = camPose.transformPoint(floatArrayOf(camX, camY, camZ))
                    gridPositions[idx * 3] = worldP[0]
                    gridPositions[idx * 3 + 1] = worldP[1]
                    gridPositions[idx * 3 + 2] = worldP[2]
                    gridValid[idx] = true
                  } else {
                    gridValid[idx] = false
                  }
                }
              }

              // Collect mesh triangles grouped by surface classification
              val semanticTris = mutableMapOf<MeshSurfaceCategory, MutableList<FloatArray>>()

              for (gy in 0 until gridH - 1) {
                for (gx in 0 until gridW - 1) {
                  val i00 = gy * gridW + gx
                  val i10 = gy * gridW + (gx + 1)
                  val i01 = (gy + 1) * gridW + gx
                  val i11 = (gy + 1) * gridW + (gx + 1)

                  if (gridValid[i00] && gridValid[i10] && gridValid[i01]) {
                    val p0 = floatArrayOf(gridPositions[i00 * 3], gridPositions[i00 * 3 + 1], gridPositions[i00 * 3 + 2])
                    val p1 = floatArrayOf(gridPositions[i10 * 3], gridPositions[i10 * 3 + 1], gridPositions[i10 * 3 + 2])
                    val p2 = floatArrayOf(gridPositions[i01 * 3], gridPositions[i01 * 3 + 1], gridPositions[i01 * 3 + 2])

                    val d1 = Math.abs(p0[2] - p1[2]); val d2 = Math.abs(p0[2] - p2[2])
                    if (d1 < 0.20f && d2 < 0.20f) {
                      val cat = classifyTriangleCategory(p0, p1, p2)
                      val list = semanticTris.getOrPut(cat) { mutableListOf() }
                      list.add(p0); list.add(p1); list.add(p2)
                    }
                  }

                  if (gridValid[i10] && gridValid[i11] && gridValid[i01]) {
                    val p0 = floatArrayOf(gridPositions[i10 * 3], gridPositions[i10 * 3 + 1], gridPositions[i10 * 3 + 2])
                    val p1 = floatArrayOf(gridPositions[i11 * 3], gridPositions[i11 * 3 + 1], gridPositions[i11 * 3 + 2])
                    val p2 = floatArrayOf(gridPositions[i01 * 3], gridPositions[i01 * 3 + 1], gridPositions[i01 * 3 + 2])

                    val d1 = Math.abs(p0[2] - p1[2]); val d2 = Math.abs(p0[2] - p2[2])
                    if (d1 < 0.20f && d2 < 0.20f) {
                      val cat = classifyTriangleCategory(p0, p1, p2)
                      val list = semanticTris.getOrPut(cat) { mutableListOf() }
                      list.add(p0); list.add(p1); list.add(p2)
                    }
                  }
                }
              }

              for ((cat, vertList) in semanticTris) {
                if (vertList.size >= 9) {
                  val numTris = vertList.size / 3
                  val numVerts = vertList.size
                  val vBuffer = ByteBuffer.allocateDirect(numVerts * 3 * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                  val iBuffer = ByteBuffer.allocateDirect(numTris * 3 * 4)
                    .order(ByteOrder.nativeOrder())
                    .asIntBuffer()

                  var sumX = 0f; var sumY = 0f; var sumZ = 0f
                  var totalTriArea = 0f

                  for (i in 0 until numTris) {
                    val p0 = vertList[i * 3]
                    val p1 = vertList[i * 3 + 1]
                    val p2 = vertList[i * 3 + 2]

                    vBuffer.put(p0); vBuffer.put(p1); vBuffer.put(p2)
                    iBuffer.put(i * 3); iBuffer.put(i * 3 + 1); iBuffer.put(i * 3 + 2)

                    sumX += (p0[0] + p1[0] + p2[0]) / 3f
                    sumY += (p0[1] + p1[1] + p2[1]) / 3f
                    sumZ += (p0[2] + p1[2] + p2[2]) / 3f

                    val abx = p1[0] - p0[0]; val aby = p1[1] - p0[1]; val abz = p1[2] - p0[2]
                    val acx = p2[0] - p0[0]; val acy = p2[1] - p0[1]; val acz = p2[2] - p0[2]
                    val crossX = aby * acz - abz * acy
                    val crossY = abz * acx - abx * acz
                    val crossZ = abx * acy - aby * acx
                    totalTriArea += 0.5f * Math.sqrt((crossX * crossX + crossY * crossY + crossZ * crossZ).toDouble()).toFloat()
                  }
                  vBuffer.position(0)
                  iBuffer.position(0)

                  val center = floatArrayOf(sumX / numTris, sumY / numTris, sumZ / numTris)
                  val chunk = MeshChunk(
                    id = "env_mesh_${cat.name.lowercase()}",
                    sourceType = GeometrySourceType.ENVIRONMENTAL_3D_MESH,
                    category = cat,
                    vertexCount = numVerts,
                    triangleCount = numTris,
                    centerPosition = center,
                    surfaceAreaSquareMeters = totalTriArea,
                    vertexBuffer = vBuffer,
                    indexBuffer = iBuffer
                  )
                  environmental3dChunks[chunk.id] = chunk

                  if (cat == MeshSurfaceCategory.FLOOR) hasFloor = true
                  if (cat == MeshSurfaceCategory.WALL) hasWall = true
                  if (cat == MeshSurfaceCategory.TABLE_SURFACE || cat == MeshSurfaceCategory.DESK_OR_COUNTER) hasTable = true
                }
              }
            }
          }
        } catch (_: Throwable) {
        } finally {
          depthImage?.close()
        }
      }

      val totalChunks = detectedPlaneChunks.size + streetscapeChunks.size + environmental3dChunks.size
      val real3dMeshCount = streetscapeChunks.size + environmental3dChunks.size
      val hasReal3dMesh = real3dMeshCount > 0

      val localMeshArea = environmental3dChunks.values.sumOf { it.surfaceAreaSquareMeters.toDouble() }.toFloat()
      val localMeshTris = environmental3dChunks.values.sumOf { it.triangleCount }
      val localMeshVerts = environmental3dChunks.values.sumOf { it.vertexCount }

      totalVerts += localMeshVerts
      totalTris += localMeshTris
      totalArea += localMeshArea

      // Strictly separate the 5 distinct states:
      // State 1: Plane Detection (convex 2D polygons)
      val isPlaneDetectionActive = detectedPlaneChunks.isNotEmpty()
      // State 2: Streetscape Geometry (outdoor mesh)
      val isStreetscapeActive = streetscapeChunks.isNotEmpty()
      // State 3: Local Environmental Mesh (volumetric point cloud / depth chunks)
      val isLocalMeshActive = environmental3dChunks.isNotEmpty()
      // State 4: Dense Local Reconstruction (substantial local geometric coverage)
      val isDenseLocalReconstruction = isLocalMeshActive && localMeshTris >= 300 && localMeshArea >= 4.0f
      // State 5: Full Scene Reconstruction (requires dense local coverage AND multi-surface geometry)
      val isFull3dScene = isDenseLocalReconstruction &&
                          localMeshArea >= 8.0f &&
                          localMeshTris >= 600 &&
                          hasFloor &&
                          (hasWall || hasTable)

      telemetry = ReconstructionTelemetry(
        isReconstructionActive = totalChunks > 0,
        isPlaneDetectionActive = isPlaneDetectionActive,
        isStreetscapeGeometryActive = isStreetscapeActive,
        isLocalEnvironmentalMeshActive = isLocalMeshActive,
        isDenseLocalReconstructionActive = isDenseLocalReconstruction,
        isDenseLocalMeshActive = isDenseLocalReconstruction,
        isFull3dSceneReconstruction = isFull3dScene,
        hasReal3dMeshGeometry = hasReal3dMesh,
        detectedPlanesCount = detectedPlaneChunks.size,
        streetscapeGeometriesCount = streetscapeChunks.size,
        denseMeshChunksCount = environmental3dChunks.size,
        totalChunks = totalChunks,
        totalVertices = totalVerts,
        totalTriangles = totalTris,
        totalSurfaceAreaSqMeters = totalArea,
        localMeshAreaSqMeters = localMeshArea,
        hasFloorPlane = hasFloor,
        hasWallPlane = hasWall,
        hasTableSurface = hasTable
      )
    } catch (e: Exception) {
      Log.d(TAG, "Environmental mesh update: ${e.message}")
    }
  }

  private fun classifyTriangleCategory(p0: FloatArray, p1: FloatArray, p2: FloatArray): MeshSurfaceCategory {
    val abx = p1[0] - p0[0]; val aby = p1[1] - p0[1]; val abz = p1[2] - p0[2]
    val acx = p2[0] - p0[0]; val acy = p2[1] - p0[1]; val acz = p2[2] - p0[2]
    val cx = aby * acz - abz * acy
    val cy = abz * acx - abx * acz
    val cz = abx * acy - aby * acx
    val len = Math.sqrt((cx * cx + cy * cy + cz * cz).toDouble()).toFloat()
    val ny = if (len > 0.0001f) cy / len else 0f
    val avgY = (p0[1] + p1[1] + p2[1]) / 3f

    return when {
      ny > 0.6f -> if (avgY <= FLOOR_HEIGHT_THRESHOLD_METERS) MeshSurfaceCategory.FLOOR else MeshSurfaceCategory.TABLE_SURFACE
      ny < -0.6f -> MeshSurfaceCategory.CEILING
      Math.abs(ny) < 0.35f -> MeshSurfaceCategory.WALL
      else -> MeshSurfaceCategory.GENERIC_OBSTACLE
    }
  }

  fun clear() {
    detectedPlaneChunks.clear()
    streetscapeChunks.clear()
    environmental3dChunks.clear()
    telemetry = ReconstructionTelemetry()
  }
}
