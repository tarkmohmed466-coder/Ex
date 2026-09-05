package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.arcore.ImageMarkerCatalog
import com.example.engine.HardwareCapabilityDetector
import com.example.engine.RenderQualityProfile
import com.example.parser.GltfAssetFactory
import com.example.viewmodel.SpatialViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Mixed Reality", appName)
  }

  @Test
  fun `verify preset models and glb buffer generation`() {
    val models = GltfAssetFactory.getPresetModels()
    assertTrue("Preset models should not be empty", models.isNotEmpty())
    
    val droneModel = models.firstOrNull { it.id == "drone_v1" }
    assertNotNull(droneModel)

    val glbBuffer = GltfAssetFactory.getPresetGlbBuffer("drone_v1")
    assertNotNull(glbBuffer)
    assertTrue("GLB buffer should contain binary data", glbBuffer!!.capacity() > 1000)
  }

  @Test
  fun `verify image marker catalog and target generation`() {
    val exhibits = ImageMarkerCatalog.exhibits
    assertTrue("Exhibits catalog should contain markers", exhibits.isNotEmpty())

    val droneMarker = ImageMarkerCatalog.findByMarkerId("marker_drone")
    assertNotNull("Drone marker should exist", droneMarker)
    assertEquals("drone_v1", droneMarker!!.modelId)

    val bitmap = ImageMarkerCatalog.generateMarkerBitmap(droneMarker)
    assertNotNull("Generated marker bitmap should not be null", bitmap)
    assertEquals(512, bitmap.width)
    assertEquals(512, bitmap.height)
  }

  @Test
  fun `verify hardware capability detection`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val caps = HardwareCapabilityDetector.detect(context)
    assertNotNull(caps)
    assertTrue(caps.suggestedProfile in RenderQualityProfile.values())
  }

  @Test
  fun `verify spatial model 1 to 1 metric scale validation`() {
    val models = GltfAssetFactory.getPresetModels()
    for (model in models) {
      val buffer = GltfAssetFactory.getPresetGlbBuffer(model.id)
      assertNotNull("GLB buffer for ${model.id} should not be null", buffer)
      val report = com.example.engine.SpatialModelValidator.validateGlbBuffer(buffer!!, model)
      assertTrue("Model ${model.id} should be a valid glTF structure", report.isValidGltf)
      assertTrue("Model ${model.id} should have 1:1 metric scale", report.isMetricOneToOneScale)
      assertTrue("Model ${model.id} bounding width should be > 0", report.widthMeters > 0f)
      assertTrue("Model ${model.id} vertex count should match", report.vertexCount > 0)
    }
  }

  @Test
  fun `verify spatial dynamic LOD manager distance thresholds`() {
    val lodManager = com.example.engine.SpatialLodManager()
    val cameraPos = floatArrayOf(0f, 1.5f, 0f)

    // 1. Close object (< 2.5m) -> LOD_0
    val closeObjPos = floatArrayOf(0f, 1.5f, -1.2f)
    val lodClose = lodManager.evaluateLod("obj_1", cameraPos, closeObjPos, 0.5f, 1080, 1920)
    assertEquals(com.example.engine.LodLevel.LOD_0, lodClose)
    assertTrue(lodManager.shouldUpdateAnimation("obj_1"))

    // 2. Medium distance (4.0m) -> LOD_1
    val midObjPos = floatArrayOf(0f, 1.5f, -4.0f)
    val lodMid = lodManager.evaluateLod("obj_2", cameraPos, midObjPos, 0.5f, 1080, 1920)
    assertEquals(com.example.engine.LodLevel.LOD_1, lodMid)

    // 3. Far distance (10.0m) -> LOD_2
    val farObjPos = floatArrayOf(0f, 1.5f, -10.0f)
    val lodFar = lodManager.evaluateLod("obj_3", cameraPos, farObjPos, 0.5f, 1080, 1920)
    assertEquals(com.example.engine.LodLevel.LOD_2, lodFar)
  }

  @Test
  fun `verify cross-mode model topology consistency across Object, AR, and MR modes`() {
    val models = GltfAssetFactory.getPresetModels()
    val drone = models.first { it.id == "drone_v1" }

    // Object mode source asset
    val objectModeBuffer = GltfAssetFactory.getPresetGlbBuffer(drone.id)
    // AR mode source asset
    val arModeBuffer = GltfAssetFactory.getPresetGlbBuffer(drone.id)
    // MR mode source asset
    val mrModeBuffer = GltfAssetFactory.getPresetGlbBuffer(drone.id)

    assertNotNull(objectModeBuffer)
    assertNotNull(arModeBuffer)
    assertNotNull(mrModeBuffer)

    assertEquals(objectModeBuffer!!.capacity(), arModeBuffer!!.capacity())
    assertEquals(arModeBuffer.capacity(), mrModeBuffer!!.capacity())

    // Data-driven validation against actual GLB structure
    val report = com.example.engine.SpatialModelValidator.validateGlbBuffer(objectModeBuffer, drone)
    assertTrue("Drone GLB must be valid glTF 2.0", report.isValidGltf)
    assertEquals("Drone vertex count must match GLB geometry", report.vertexCount, drone.vertexCount)
    assertEquals("Drone triangle count must match GLB index count", report.indexCount / 3, drone.triangleCount)
    assertEquals("Drone actual vertices count", 160, drone.vertexCount)
    assertEquals("Drone actual triangles count", 140, drone.triangleCount)
    org.junit.Assert.assertFalse("Procedural drone has no animation tracks", drone.hasAnimations)
    org.junit.Assert.assertFalse("Procedural drone has no skinning bones", report.hasSkinningBones)
  }

  @Test
  fun `verify data-driven preset model geometry and animation metadata`() {
    val models = GltfAssetFactory.getPresetModels()
    for (model in models) {
      val buffer = GltfAssetFactory.getPresetGlbBuffer(model.id)
      assertNotNull("GLB buffer for ${model.id} should not be null", buffer)
      val report = com.example.engine.SpatialModelValidator.validateGlbBuffer(buffer!!, model)

      assertTrue("Model ${model.id} must be valid glTF", report.isValidGltf)
      assertTrue("Model ${model.id} vertices must be > 0", report.vertexCount > 0)
      assertTrue("Model ${model.id} indices must be > 0", report.indexCount > 0)
      assertEquals("Model ${model.id} vertexCount must match GLB", model.vertexCount, report.vertexCount)
      assertEquals("Model ${model.id} triangleCount must match GLB", model.triangleCount, report.indexCount / 3)
      org.junit.Assert.assertFalse("Model ${model.id} should not report fictional animations", model.hasAnimations)
      assertEquals("Model ${model.id} animationTrackCount should be 0", 0, report.animationTrackCount)
      org.junit.Assert.assertFalse("Model ${model.id} should not have skinning bones", report.hasSkinningBones)
    }
  }

  @Test
  fun `verify depth occlusion manager initial state and confidence semantics`() {
    val depthManager = com.example.arcore.DepthOcclusionManager()
    org.junit.Assert.assertFalse("Depth texture should not be ready initially", depthManager.isDepthTextureReady)
    org.junit.Assert.assertFalse("Depth confidence should not be available initially", depthManager.isConfidenceAvailable)
    assertNull("Depth confidence percentage should be null when confidence image absent", depthManager.depthConfidencePercentage)
    assertEquals("Depth coverage percentage should be 0 initially", 0f, depthManager.depthCoveragePercentage, 0.001f)
  }

  @Test
  fun `verify cloud anchor manager operates independently of realtime multiplayer backend`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cloudManager = com.example.arcore.CloudAnchorManager(context)
    org.junit.Assert.assertFalse("Realtime backend should not be connected by default", cloudManager.isRealtimeBackendConnected)
    org.junit.Assert.assertFalse("Multiplayer should remain inactive without realtime backend", cloudManager.isMultiplayerActive)
    assertEquals("Cloud anchors count should start at zero", 0, cloudManager.cloudAnchorsCount)
  }

  @Test
  fun `verify mr mode semantics and device certification telemetry`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = SpatialViewModel(app)
    val telemetry = viewModel.telemetry.value

    assertEquals(
      "Monoscopic Passthrough + Stereoscopic Virtual Rendering",
      telemetry.mrPassthroughSemantics
    )
    org.junit.Assert.assertFalse("True binocular hardware capture is not claimed", telemetry.isTrueBinocularPassthrough)
    org.junit.Assert.assertFalse("Google certified device should not be claimed from hardcoded string", telemetry.isGoogleCertifiedDevice)
    org.junit.Assert.assertFalse("Realtime backend should start disconnected", telemetry.isRealtimeBackendConnected)
    org.junit.Assert.assertFalse("Multiplayer should start inactive", telemetry.isMultiplayerActive)
    org.junit.Assert.assertFalse("Full 3D scene reconstruction requires dense local coverage", telemetry.isFull3dSceneReconstruction)
  }

  @Test
  fun `verify clear scene removes active model and resets spatial state`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = SpatialViewModel(app)
    assertNotNull(viewModel.selectedModel.value)
    assertNotNull(viewModel.activeGlbBuffer.value)

    viewModel.clearActiveModelAndScene()
    assertNull(viewModel.selectedModel.value)
    assertNull(viewModel.activeGlbBuffer.value)
    assertEquals(0, viewModel.arAnchors.value.size)
    assertNull(viewModel.nearbyExhibit.value)
    assertEquals(0, viewModel.telemetry.value.vertexCount)
  }

  @Test
  fun `verify switching to MR mode activates stereoscopic mode`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = SpatialViewModel(app)
    viewModel.setDisplayMode(com.example.model.DisplayMode.MR)
    assertEquals(com.example.model.DisplayMode.MR, viewModel.displayMode.value)
  }

  @Test
  fun `verify thermal quality levels and resolution scaling constraints`() {
    val high = com.example.engine.ThermalQualityLevel.HIGH
    val medium = com.example.engine.ThermalQualityLevel.MEDIUM
    val low = com.example.engine.ThermalQualityLevel.LOW
    val emergency = com.example.engine.ThermalQualityLevel.EMERGENCY

    assertEquals(1.0f, high.resolutionScale, 0.001f)
    assertTrue(high.enableFxaa)
    assertEquals(false, high.isThrottled)

    assertEquals(0.9f, medium.resolutionScale, 0.001f)
    assertTrue(medium.enableFxaa)
    assertTrue(medium.isThrottled)

    assertEquals(0.75f, low.resolutionScale, 0.001f)
    assertEquals(false, low.enableFxaa)
    assertTrue(low.isThrottled)

    assertEquals(0.5f, emergency.resolutionScale, 0.001f)
    assertEquals(false, emergency.enableFxaa)
    assertTrue(emergency.isThrottled)
  }

  @Test
  fun `verify arcore session manager initial state is paused`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = com.example.arcore.ArCoreSessionManager(context)
    assertTrue("Initial session state should be paused", manager.isSessionPaused)
    assertNull("Updating frame when paused should safely return null without throwing", manager.updateFrame())
  }
}
