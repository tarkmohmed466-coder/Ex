package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
  fun `verify bundled spatial models`() {
    val models = com.example.parser.ProceduralModels.getBundledModels()
    assertEquals(true, models.isNotEmpty())
    assertEquals(true, models.any { it.title.contains("Visor") })
  }
}
