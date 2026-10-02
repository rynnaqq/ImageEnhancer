package dev.localphoto.enhancer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphoto.enhancer.ai.FaceRestorer
import dev.localphoto.enhancer.ai.ModelManager
import dev.localphoto.enhancer.processing.ProcessingControl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FaceDetailGuardInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun largeDetailedPortraitIsDetectedButPreservedWithoutOpeningFaceModel() = runBlocking {
        val source = astronaut(1_024)
        val before = pixels(source)
        val models = ModelManager(context)
        try {
            assertFalse(models.catalog.value.getValue("face-swinir").ready)
            val result = FaceRestorer(models).restore(source, 100f, null, ProcessingControl()) { }
            try {
                assertTrue("large portrait face was not detected", result.detectedFaces >= 1)
                assertTrue("downsampled aligned face was not preserved", result.detailPreservedFaces >= 1)
                assertEquals(0, result.protectedFaces)
                assertEquals(0, result.restoredFaces)
                assertNotSame(source, result.bitmap)
                assertArrayEquals(before, pixels(source))
                assertArrayEquals(before, pixels(result.bitmap))
                assertFalse("detail guard must avoid opening FaceSwinIR",
                    models.catalog.value.getValue("face-swinir").ready)
            } finally {
                result.bitmap.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    private fun astronaut(size: Int): Bitmap {
        val raw = InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/astronaut.png").use {
            requireNotNull(BitmapFactory.decodeStream(it))
        }
        val scaled = Bitmap.createScaledBitmap(raw, size, size, true)
        val copy = requireNotNull(scaled.copy(Bitmap.Config.ARGB_8888, true))
        if (scaled !== raw) scaled.recycle()
        raw.recycle()
        return copy
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
