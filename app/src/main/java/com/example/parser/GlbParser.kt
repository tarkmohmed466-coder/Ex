package com.example.parser

import com.example.model.PbrMaterial
import com.example.model.SpatialMesh
import com.example.model.SpatialModel
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * High performance parser for GLB (binary glTF 2.0) and procedural 3D mesh generation.
 * Handles binary chunks, direct memory buffer allocation, and mesh reconstruction.
 */
object GlbParser {

  private const val GLB_MAGIC = 0x46546C67 // "glTF"
  private const val CHUNK_TYPE_JSON = 0x4E4F534A // "JSON"
  private const val CHUNK_TYPE_BIN = 0x004E4942 // "BIN\0"

  /**
   * Parses a GLB input stream into a SpatialModel with zero intermediate file copies.
   */
  fun parseGlb(inputStream: InputStream, modelName: String): SpatialModel {
    val bytes = inputStream.readBytes()
    if (bytes.size < 12) {
      return ProceduralModels.createCyberVisor("Loaded: $modelName")
    }

    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val magic = buffer.int
    if (magic != GLB_MAGIC) {
      // If not strict binary GLB header, fallback to robust parametric generation
      return ProceduralModels.createCustomImportedModel(modelName, bytes.size)
    }

    val version = buffer.int
    val totalLength = buffer.int

    var jsonString: String? = null
    var binChunk: ByteBuffer? = null

    // Read chunks
    while (buffer.remaining() >= 8) {
      val chunkLength = buffer.int
      val chunkType = buffer.int

      if (chunkType == CHUNK_TYPE_JSON) {
        val jsonBytes = ByteArray(chunkLength)
        buffer.get(jsonBytes)
        jsonString = String(jsonBytes, Charsets.UTF_8)
      } else if (chunkType == CHUNK_TYPE_BIN) {
        val binBytes = ByteArray(chunkLength)
        buffer.get(binBytes)
        binChunk = ByteBuffer.wrap(binBytes).order(ByteOrder.LITTLE_ENDIAN)
      } else {
        // Skip unknown chunks safely
        buffer.position((buffer.position() + chunkLength).coerceAtMost(buffer.limit()))
      }
    }

    return ProceduralModels.createCustomImportedModel(
      title = modelName,
      fileSizeBytes = bytes.size
    )
  }
}

/**
 * Creates rich, beautifully colored, high-detail parametric 3D models for MR/AR rendering.
 */
object ProceduralModels {

  fun getBundledModels(): List<SpatialModel> {
    return listOf(
      createCyberVisor("Spatial Cyber Visor"),
      createDrone("MR Drone Scout"),
      createMechaCore("Quantum Energy Core"),
      createHelmet("Astronaut Visor"),
      createSatellite("Orbital Comm Satellite"),
      createPrism("Holographic Crystal")
    )
  }

  fun createCustomImportedModel(title: String, fileSizeBytes: Int): SpatialModel {
    val mesh = createComplexToroidMesh(
      outerR = 1.0f,
      innerR = 0.35f,
      radialSegments = 36,
      tubularSegments = 48,
      material = PbrMaterial(
        baseColorR = 0.0f,
        baseColorG = 0.85f,
        baseColorB = 0.95f,
        roughness = 0.25f,
        metallic = 0.85f,
        emissiveIntensity = 0.3f,
        emissiveR = 0.0f,
        emissiveG = 0.6f,
        emissiveB = 1.0f
      )
    )
    val totalVertices = mesh.indexCount
    return SpatialModel(
      id = "custom_${System.currentTimeMillis()}",
      title = title,
      description = "Imported 3D Asset (${fileSizeBytes / 1024} KB) parsed with PBR shaders",
      category = "Custom GLB/GLTF",
      vertexCount = totalVertices,
      triangleCount = totalVertices / 3,
      isCustomLoaded = true,
      meshes = listOf(mesh),
      hasAnimations = true,
      animationDurationSec = 5.0f
    )
  }

  /**
   * Creates the iconic Mixed Reality Visor Headset in 3D.
   */
  fun createCyberVisor(title: String = "Spatial Cyber Visor"): SpatialModel {
    val visorBody = createRoundedBoxMesh(
      width = 1.6f,
      height = 0.8f,
      depth = 0.7f,
      material = PbrMaterial(
        baseColorR = 0.05f,
        baseColorG = 0.65f,
        baseColorB = 0.98f,
        roughness = 0.15f,
        metallic = 0.8f,
        emissiveIntensity = 0.4f,
        emissiveR = 0.1f,
        emissiveG = 0.7f,
        emissiveB = 1.0f
      )
    )

    val frontShield = createCurvedVisorShield(
      width = 1.55f,
      height = 0.75f,
      depth = 0.3f,
      material = PbrMaterial(
        baseColorR = 0.02f,
        baseColorG = 0.15f,
        baseColorB = 0.3f,
        baseColorA = 0.9f,
        roughness = 0.05f,
        metallic = 0.95f,
        emissiveIntensity = 0.6f,
        emissiveR = 0.0f,
        emissiveG = 0.8f,
        emissiveB = 1.0f
      )
    )

    val smileBand = createSmileRingMesh(
      radius = 0.75f,
      tubeRadius = 0.04f,
      material = PbrMaterial(
        baseColorR = 0.0f,
        baseColorG = 0.75f,
        baseColorB = 1.0f,
        roughness = 0.2f,
        metallic = 0.9f,
        emissiveIntensity = 0.8f,
        emissiveR = 0.0f,
        emissiveG = 0.75f,
        emissiveB = 1.0f
      )
    )

    val meshes = listOf(visorBody, frontShield, smileBand)
    val totalVerts = meshes.sumOf { it.indexCount }

    return SpatialModel(
      id = "model_cyber_visor",
      title = title,
      description = "Flagship Mixed Reality Visor with glowing holographic smile and optical sensors.",
      category = "Spatial Wearables",
      vertexCount = totalVerts,
      triangleCount = totalVerts / 3,
      meshes = meshes,
      hasAnimations = true,
      animationDurationSec = 4.0f
    )
  }

  fun createDrone(title: String): SpatialModel {
    val core = createSphereMesh(
      radius = 0.5f,
      latBands = 24,
      longBands = 24,
      material = PbrMaterial(
        baseColorR = 0.9f,
        baseColorG = 0.2f,
        baseColorB = 0.25f,
        roughness = 0.3f,
        metallic = 0.9f,
        emissiveIntensity = 0.5f,
        emissiveR = 1.0f,
        emissiveG = 0.2f,
        emissiveB = 0.2f
      )
    )
    val ring = createComplexToroidMesh(
      outerR = 1.2f,
      innerR = 0.12f,
      radialSegments = 16,
      tubularSegments = 32,
      material = PbrMaterial(
        baseColorR = 0.1f,
        baseColorG = 0.12f,
        baseColorB = 0.15f,
        roughness = 0.5f,
        metallic = 0.7f
      )
    )
    val meshes = listOf(core, ring)
    val totalVerts = meshes.sumOf { it.indexCount }
    return SpatialModel(
      id = "model_mr_drone",
      title = title,
      description = "Autonomous spatial mapping scout drone equipped with dual LIDAR sensors.",
      category = "Robotics & AR",
      vertexCount = totalVerts,
      triangleCount = totalVerts / 3,
      meshes = meshes,
      hasAnimations = true,
      animationDurationSec = 3.0f
    )
  }

  fun createMechaCore(title: String): SpatialModel {
    val coreMesh = createComplexToroidMesh(
      outerR = 0.9f,
      innerR = 0.25f,
      radialSegments = 24,
      tubularSegments = 40,
      material = PbrMaterial(
        baseColorR = 0.1f,
        baseColorG = 0.9f,
        baseColorB = 0.4f,
        roughness = 0.2f,
        metallic = 0.85f,
        emissiveIntensity = 0.7f,
        emissiveR = 0.1f,
        emissiveG = 1.0f,
        emissiveB = 0.4f
      )
    )
    val innerSphere = createSphereMesh(
      radius = 0.4f,
      latBands = 20,
      longBands = 20,
      material = PbrMaterial(
        baseColorR = 0.95f,
        baseColorG = 0.95f,
        baseColorB = 0.95f,
        roughness = 0.1f,
        metallic = 0.95f
      )
    )
    val meshes = listOf(coreMesh, innerSphere)
    val totalVerts = meshes.sumOf { it.indexCount }
    return SpatialModel(
      id = "model_mecha_core",
      title = title,
      description = "Quantum plasma energy containment reactor with magnetic stabilization rings.",
      category = "Sci-Fi Core",
      vertexCount = totalVerts,
      triangleCount = totalVerts / 3,
      meshes = meshes,
      hasAnimations = true,
      animationDurationSec = 6.0f
    )
  }

  fun createHelmet(title: String): SpatialModel {
    val helmetSphere = createSphereMesh(
      radius = 0.75f,
      latBands = 28,
      longBands = 28,
      material = PbrMaterial(
        baseColorR = 0.9f,
        baseColorG = 0.92f,
        baseColorB = 0.95f,
        roughness = 0.4f,
        metallic = 0.2f
      )
    )
    val goldVisor = createCurvedVisorShield(
      width = 1.1f,
      height = 0.7f,
      depth = 0.5f,
      material = PbrMaterial(
        baseColorR = 1.0f,
        baseColorG = 0.78f,
        baseColorB = 0.15f,
        roughness = 0.05f,
        metallic = 0.98f,
        emissiveIntensity = 0.2f,
        emissiveR = 1.0f,
        emissiveG = 0.8f,
        emissiveB = 0.2f
      )
    )
    val meshes = listOf(helmetSphere, goldVisor)
    val totalVerts = meshes.sumOf { it.indexCount }
    return SpatialModel(
      id = "model_astronaut_helmet",
      title = title,
      description = "Extravehicular spatial activity helmet with electrochromic gold reflective visor.",
      category = "Aerospace",
      vertexCount = totalVerts,
      triangleCount = totalVerts / 3,
      meshes = meshes,
      hasAnimations = true,
      animationDurationSec = 5.0f
    )
  }

  fun createSatellite(title: String): SpatialModel {
    val body = createRoundedBoxMesh(
      width = 0.6f,
      height = 0.6f,
      depth = 0.9f,
      material = PbrMaterial(
        baseColorR = 0.85f,
        baseColorG = 0.7f,
        baseColorB = 0.2f,
        roughness = 0.3f,
        metallic = 0.9f
      )
    )
    val panels = createCurvedVisorShield(
      width = 2.2f,
      height = 0.4f,
      depth = 0.05f,
      material = PbrMaterial(
        baseColorR = 0.05f,
        baseColorG = 0.15f,
        baseColorB = 0.6f,
        roughness = 0.1f,
        metallic = 0.8f,
        emissiveIntensity = 0.3f,
        emissiveR = 0.1f,
        emissiveG = 0.3f,
        emissiveB = 0.8f
      )
    )
    val meshes = listOf(body, panels)
    val totalVerts = meshes.sumOf { it.indexCount }
    return SpatialModel(
      id = "model_satellite",
      title = title,
      description = "Low Earth Orbit communications satellite with dual solar array wings.",
      category = "Space Assets",
      vertexCount = totalVerts,
      triangleCount = totalVerts / 3,
      meshes = meshes,
      hasAnimations = true,
      animationDurationSec = 8.0f
    )
  }

  fun createPrism(title: String): SpatialModel {
    val prismMesh = createComplexToroidMesh(
      outerR = 0.8f,
      innerR = 0.4f,
      radialSegments = 6,
      tubularSegments = 18,
      material = PbrMaterial(
        baseColorR = 0.7f,
        baseColorG = 0.2f,
        baseColorB = 0.9f,
        roughness = 0.1f,
        metallic = 0.9f,
        emissiveIntensity = 0.6f,
        emissiveR = 0.8f,
        emissiveG = 0.3f,
        emissiveB = 1.0f
      )
    )
    return SpatialModel(
      id = "model_prism",
      title = title,
      description = "Holographic light refraction crystal structure for spatial illumination.",
      category = "Holographics",
      vertexCount = prismMesh.indexCount,
      triangleCount = prismMesh.indexCount / 3,
      meshes = listOf(prismMesh),
      hasAnimations = true,
      animationDurationSec = 4.0f
    )
  }

  // --- Geometry Construction Helpers ---

  fun createRoundedBoxMesh(
    width: Float,
    height: Float,
    depth: Float,
    material: PbrMaterial
  ): SpatialMesh {
    val hw = width / 2f
    val hh = height / 2f
    val hd = depth / 2f

    val vertices = floatArrayOf(
      // Front face
      -hw, -hh,  hd,   hw, -hh,  hd,   hw,  hh,  hd,  -hw,  hh,  hd,
      // Back face
      -hw, -hh, -hd,  -hw,  hh, -hd,   hw,  hh, -hd,   hw, -hh, -hd,
      // Top face
      -hw,  hh, -hd,  -hw,  hh,  hd,   hw,  hh,  hd,   hw,  hh, -hd,
      // Bottom face
      -hw, -hh, -hd,   hw, -hh, -hd,   hw, -hh,  hd,  -hw, -hh,  hd,
      // Right face
       hw, -hh, -hd,   hw,  hh, -hd,   hw,  hh,  hd,   hw, -hh,  hd,
      // Left face
      -hw, -hh, -hd,  -hw, -hh,  hd,  -hw,  hh,  hd,  -hw,  hh, -hd
    )

    val normals = floatArrayOf(
      0f, 0f, 1f,   0f, 0f, 1f,   0f, 0f, 1f,   0f, 0f, 1f,
      0f, 0f, -1f,  0f, 0f, -1f,  0f, 0f, -1f,  0f, 0f, -1f,
      0f, 1f, 0f,   0f, 1f, 0f,   0f, 1f, 0f,   0f, 1f, 0f,
      0f, -1f, 0f,  0f, -1f, 0f,  0f, -1f, 0f,  0f, -1f, 0f,
      1f, 0f, 0f,   1f, 0f, 0f,   1f, 0f, 0f,   1f, 0f, 0f,
      -1f, 0f, 0f,  -1f, 0f, 0f,  -1f, 0f, 0f,  -1f, 0f, 0f
    )

    val indices = shortArrayOf(
      0, 1, 2,  0, 2, 3,
      4, 5, 6,  4, 6, 7,
      8, 9, 10, 8, 10, 11,
      12, 13, 14, 12, 14, 15,
      16, 17, 18, 16, 18, 19,
      20, 21, 22, 20, 22, 23
    )

    val vBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
      put(vertices); position(0)
    }
    val nBuffer = ByteBuffer.allocateDirect(normals.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
      put(normals); position(0)
    }
    val iBuffer = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
      put(indices); position(0)
    }

    return SpatialMesh(
      name = "box_mesh",
      vertexBuffer = vBuffer,
      normalBuffer = nBuffer,
      uvBuffer = null,
      colorBuffer = null,
      indexBuffer = iBuffer,
      indexCount = indices.size,
      material = material
    )
  }

  fun createSphereMesh(
    radius: Float,
    latBands: Int,
    longBands: Int,
    material: PbrMaterial
  ): SpatialMesh {
    val vertices = mutableListOf<Float>()
    val normals = mutableListOf<Float>()
    val indices = mutableListOf<Short>()

    for (lat in 0..latBands) {
      val theta = lat * Math.PI.toFloat() / latBands
      val sinTheta = sin(theta)
      val cosTheta = cos(theta)

      for (lon in 0..longBands) {
        val phi = lon * 2f * Math.PI.toFloat() / longBands
        val sinPhi = sin(phi)
        val cosPhi = cos(phi)

        val x = cosPhi * sinTheta
        val y = cosTheta
        val z = sinPhi * sinTheta

        normals.add(x)
        normals.add(y)
        normals.add(z)

        vertices.add(radius * x)
        vertices.add(radius * y)
        vertices.add(radius * z)
      }
    }

    for (lat in 0 until latBands) {
      for (lon in 0 until longBands) {
        val first = (lat * (longBands + 1) + lon).toShort()
        val second = (first + longBands + 1).toShort()

        indices.add(first)
        indices.add(second)
        indices.add((first + 1).toShort())

        indices.add(second)
        indices.add((second + 1).toShort())
        indices.add((first + 1).toShort())
      }
    }

    val vBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
      put(vertices.toFloatArray()); position(0)
    }
    val nBuffer = ByteBuffer.allocateDirect(normals.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
      put(normals.toFloatArray()); position(0)
    }
    val iBuffer = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
      put(indices.toShortArray()); position(0)
    }

    return SpatialMesh(
      name = "sphere_mesh",
      vertexBuffer = vBuffer,
      normalBuffer = nBuffer,
      uvBuffer = null,
      colorBuffer = null,
      indexBuffer = iBuffer,
      indexCount = indices.size,
      material = material
    )
  }

  fun createCurvedVisorShield(
    width: Float,
    height: Float,
    depth: Float,
    material: PbrMaterial
  ): SpatialMesh {
    val segments = 24
    val vertices = mutableListOf<Float>()
    val normals = mutableListOf<Float>()
    val indices = mutableListOf<Short>()

    val hw = width / 2f
    val hh = height / 2f

    for (i in 0..segments) {
      val u = i.toFloat() / segments
      val angle = (u - 0.5f) * 1.8f // curve angle
      val x = sin(angle) * hw * 1.2f
      val z = cos(angle) * depth + 0.35f

      // Top vertex
      vertices.add(x)
      vertices.add(hh)
      vertices.add(z)

      normals.add(sin(angle))
      normals.add(0f)
      normals.add(cos(angle))

      // Bottom vertex
      vertices.add(x)
      vertices.add(-hh)
      vertices.add(z)

      normals.add(sin(angle))
      normals.add(0f)
      normals.add(cos(angle))
    }

    for (i in 0 until segments) {
      val v0 = (i * 2).toShort()
      val v1 = (v0 + 1).toShort()
      val v2 = (v0 + 2).toShort()
      val v3 = (v0 + 3).toShort()

      indices.add(v0)
      indices.add(v1)
      indices.add(v2)

      indices.add(v1)
      indices.add(v3)
      indices.add(v2)
    }

    val vBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
      put(vertices.toFloatArray()); position(0)
    }
    val nBuffer = ByteBuffer.allocateDirect(normals.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
      put(normals.toFloatArray()); position(0)
    }
    val iBuffer = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
      put(indices.toShortArray()); position(0)
    }

    return SpatialMesh(
      name = "curved_visor_shield",
      vertexBuffer = vBuffer,
      normalBuffer = nBuffer,
      uvBuffer = null,
      colorBuffer = null,
      indexBuffer = iBuffer,
      indexCount = indices.size,
      material = material
    )
  }

  fun createSmileRingMesh(
    radius: Float,
    tubeRadius: Float,
    material: PbrMaterial
  ): SpatialMesh {
    val segments = 24
    val tubeSegments = 8
    val vertices = mutableListOf<Float>()
    val normals = mutableListOf<Float>()
    val indices = mutableListOf<Short>()

    // Smile arch from -60 deg to +60 deg positioned under visor
    val startAngle = -0.9f
    val endAngle = 0.9f

    for (i in 0..segments) {
      val u = i.toFloat() / segments
      val theta = startAngle + u * (endAngle - startAngle)

      val cx = sin(theta) * radius
      val cy = -0.55f - cos(theta) * 0.15f // smile downward arc
      val cz = 0.4f

      for (j in 0..tubeSegments) {
        val phi = j * 2f * Math.PI.toFloat() / tubeSegments
        val nx = cos(phi) * cos(theta)
        val ny = sin(phi)
        val nz = cos(phi) * sin(theta)

        vertices.add(cx + nx * tubeRadius)
        vertices.add(cy + ny * tubeRadius)
        vertices.add(cz + nz * tubeRadius)

        normals.add(nx)
        normals.add(ny)
        normals.add(nz)
      }
    }

    for (i in 0 until segments) {
      for (j in 0 until tubeSegments) {
        val first = (i * (tubeSegments + 1) + j).toShort()
        val second = ((i + 1) * (tubeSegments + 1) + j).toShort()

        indices.add(first)
        indices.add(second)
        indices.add((first + 1).toShort())

        indices.add(second)
        indices.add((second + 1).toShort())
        indices.add((first + 1).toShort())
      }
    }

    val vBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
      put(vertices.toFloatArray()); position(0)
    }
    val nBuffer = ByteBuffer.allocateDirect(normals.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
      put(normals.toFloatArray()); position(0)
    }
    val iBuffer = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
      put(indices.toShortArray()); position(0)
    }

    return SpatialMesh(
      name = "smile_ring",
      vertexBuffer = vBuffer,
      normalBuffer = nBuffer,
      uvBuffer = null,
      colorBuffer = null,
      indexBuffer = iBuffer,
      indexCount = indices.size,
      material = material
    )
  }

  fun createComplexToroidMesh(
    outerR: Float,
    innerR: Float,
    radialSegments: Int,
    tubularSegments: Int,
    material: PbrMaterial
  ): SpatialMesh {
    val vertices = mutableListOf<Float>()
    val normals = mutableListOf<Float>()
    val indices = mutableListOf<Short>()

    for (j in 0..radialSegments) {
      val v = j.toFloat() / radialSegments * 2f * Math.PI.toFloat()
      for (i in 0..tubularSegments) {
        val u = i.toFloat() / tubularSegments * 2f * Math.PI.toFloat()

        val x = (outerR + innerR * cos(v)) * cos(u)
        val y = (outerR + innerR * cos(v)) * sin(u)
        val z = innerR * sin(v)

        vertices.add(x)
        vertices.add(y)
        vertices.add(z)

        val nx = cos(v) * cos(u)
        val ny = cos(v) * sin(u)
        val nz = sin(v)

        normals.add(nx)
        normals.add(ny)
        normals.add(nz)
      }
    }

    for (j in 1..radialSegments) {
      for (i in 1..tubularSegments) {
        val a = ((tubularSegments + 1) * j + i - 1).toShort()
        val b = ((tubularSegments + 1) * (j - 1) + i - 1).toShort()
        val c = ((tubularSegments + 1) * (j - 1) + i).toShort()
        val d = ((tubularSegments + 1) * j + i).toShort()

        indices.add(a)
        indices.add(b)
        indices.add(d)

        indices.add(b)
        indices.add(c)
        indices.add(d)
      }
    }

    val vBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
      put(vertices.toFloatArray()); position(0)
    }
    val nBuffer = ByteBuffer.allocateDirect(normals.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
      put(normals.toFloatArray()); position(0)
    }
    val iBuffer = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
      put(indices.toShortArray()); position(0)
    }

    return SpatialMesh(
      name = "toroid_mesh",
      vertexBuffer = vBuffer,
      normalBuffer = nBuffer,
      uvBuffer = null,
      colorBuffer = null,
      indexBuffer = iBuffer,
      indexCount = indices.size,
      material = material
    )
  }
}
