package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.HardwareCapabilityDetector
import com.example.engine.RenderQualityProfile
import com.example.engine.SpatialModelValidator
import com.example.parser.GltfAssetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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
  fun `verify hardware capability detection`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val caps = HardwareCapabilityDetector.detect(context)
    assertNotNull(caps)
    assertTrue(caps.suggestedProfile in RenderQualityProfile.values())
  }
}
