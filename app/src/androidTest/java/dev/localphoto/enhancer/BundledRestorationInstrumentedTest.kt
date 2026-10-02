package dev.localphoto.enhancer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphoto.core.*
import dev.localphoto.enhancer.ai.*
import dev.localphoto.enhancer.data.*
import dev.localphoto.enhancer.processing.ProcessingControl
import dev.localphoto.enhancer.processing.ProcessingStopped
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** These tests run the bundled neural graphs on CPU; no network or inference doubles. */
@RunWith(AndroidJUnit4::class)
class BundledRestorationInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun everyToolHasAnIndependentBundledModel() {
        val manager = ModelManager(context)
        assertEquals(setOf("espcn-x3", "yunet", "face-swinir", "color-ddcolor", "repair-lama"), manager.catalog.value.keys)
        for (id in manager.catalog.value.keys) {
            val file = manager.prepareModel(id)
            assertTrue("Missing model $id", file.isFile)
            assertEquals(manager.catalog.value.getValue(id).bytes, file.length())
        }
    }

    @Test fun realRepairFillsSelectedDamageAndPreservesEveryUnselectedPixelAndAlpha() = runBlocking {
        val source = portrait(96)
        val strokes = listOf(RepairStroke(listOf(RepairPoint(.62f, .69f)), .06f))
        val mask = RestorationPixels.rasterMask(source.width, source.height, strokes)
        val original = pixels(source)
        for (index in original.indices) if (mask[index]) original[index] = Color.WHITE
        original[0] = 0x80646464.toInt()
        source.setPixels(original, 0, source.width, 0, 0, source.width, source.height)
        val before = pixels(source)
        val models = ModelManager(context)
        try {
            val result = NeuralRestoration(context, models).repair(source,
                RestorationSettings(maskStrokes = strokes), ProcessingControl()) { }
            try {
                assertTrue(result.selectedPixels > 0)
                val after = pixels(result.bitmap)
                assertTrue(after.indices.any { mask[it] && after[it] != before[it] })
                for (index in after.indices) {
                    if (!mask[index]) assertEquals("Changed unselected pixel $index", before[index], after[index])
                    assertEquals(before[index] ushr 24, after[index] ushr 24)
                }
                assertArrayEquals(before, pixels(source))
                assertTrue(models.catalog.value.getValue("repair-lama").ready)
                saveEvidence(result.bitmap, "restoration-repair.png")
            } finally { result.bitmap.recycle() }
        } finally { source.recycle() }
    }

    @Test fun realColorizationPredictsChromaWhileRetainingDimensionsLightnessAndAlpha() = runBlocking {
        val source = portrait(96)
        val gray = pixels(source).map { pixel ->
            val l = RestorationPixels.rgbToLab(pixel)[0]
            RestorationPixels.labToArgb(l, 0f, 0f)
        }.toIntArray()
        gray[0] = 0x80646464.toInt()
        source.setPixels(gray, 0, source.width, 0, 0, source.width, source.height)
        val before = pixels(source)
        val models = ModelManager(context)
        try {
            val output = NeuralRestoration(context, models).colorize(source, 70f, ProcessingControl()) { }
            try {
                assertEquals(source.width, output.width); assertEquals(source.height, output.height)
                val after = pixels(output)
                var totalChroma = 0.0
                var totalLightnessError = 0.0
                for (index in after.indices) {
                    assertEquals(before[index] ushr 24, after[index] ushr 24)
                    val a = RestorationPixels.rgbToLab(after[index])
                    val b = RestorationPixels.rgbToLab(before[index])
                    totalChroma += kotlin.math.abs(a[1]) + kotlin.math.abs(a[2])
                    totalLightnessError += kotlin.math.abs(a[0] - b[0])
                }
                assertTrue("Learned colorizer returned grayscale", totalChroma / after.size > 2.0)
                assertTrue("Lost source lightness", totalLightnessError / after.size < 2.5)
                assertArrayEquals(before, pixels(source))
                assertTrue(models.catalog.value.getValue("color-ddcolor").ready)
                saveEvidence(output, "restoration-color.png")
            } finally { output.recycle() }
        } finally { source.recycle() }
    }

    @Test fun realFaceInferenceChangesFaceDetailAndProtectsReconstructedRegions() = runBlocking {
        val source = portrait(256)
        val before = pixels(source)
        val models = ModelManager(context)
        try {
            val result = FaceRestorer(models).restore(source, 60f, null, ProcessingControl()) { }
            try {
                assertTrue("Portrait face not detected", result.detectedFaces >= 1)
                assertTrue("Face graph did not run", result.restoredFaces >= 1)
                val after = pixels(result.bitmap)
                assertEquals(before[0], after[0])
                assertTrue(after.indices.any { after[it] != before[it] })
                assertArrayEquals(before, pixels(source))
                assertTrue(models.catalog.value.getValue("face-swinir").ready)
                saveEvidence(result.bitmap, "restoration-face.png")
            } finally { result.bitmap.recycle() }
            val protection = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            try {
                val protected = FaceRestorer(models).restore(source, 60f, protection, ProcessingControl()) { }
                try {
                    assertTrue(protected.protectedFaces >= 1)
                    assertEquals(0, protected.restoredFaces)
                    assertArrayEquals(before, pixels(protected.bitmap))
                } finally { protected.bitmap.recycle() }
            } finally { protection.recycle() }
        } finally { source.recycle() }
    }

    @Test fun cancellationCannotProduceACompletedColorization() = runBlocking {
        val source = portrait(64)
        val before = pixels(source)
        val control = ProcessingControl().apply { stop() }
        try {
            try {
                NeuralRestoration(context, ModelManager(context)).colorize(source, 70f, control) { }
                fail("Cancelled colorizer returned output")
            } catch (_: ProcessingStopped) { }
            assertArrayEquals(before, pixels(source))
        } finally { source.recycle() }
    }

    @Test fun allToolsCompleteThroughEngineAndReeditingRetainsRegionProtection(): Unit = runBlocking {
        val app = context.applicationContext as EnhancerApp
        val graph = app.graph
        val id = "test-${UUID.randomUUID()}"
        try {
            val source = File(graph.files.directory(id), "original.png")
            val bitmap = portrait(128)
            try { graph.files.atomicPng(bitmap, source) } finally { bitmap.recycle() }
            val digest = sha256(source)
            val settings = EnhanceSettings(auto = false, adjustments = Adjustments(scale = 1, sharpen = 0f),
                restoration = RestorationSettings(faceStrength = 40f, colorize = true, scratchRepair = true,
                    maskStrokes = listOf(RepairStroke(listOf(RepairPoint(.62f, .69f)), .04f))))
            val project = PhotoProject(id, "restoration.png", source.absolutePath, 128, 128,
                status = ProjectStatus.PROCESSING.name, settingsJson = SettingsCodec.encode(settings))
            graph.repository.dao.insert(project)
            graph.engine.process(project, ProcessingControl())
            val completed = requireNotNull(graph.repository.dao.find(id))
            assertEquals("Engine failed: ${completed.errorCode}", ProjectStatus.COMPLETED, completed.state)
            assertEquals(128, completed.outputWidth); assertEquals(128, completed.outputHeight)
            assertEquals(digest, sha256(source))
            assertFalse(completed.galleryUri.isNullOrBlank())
            val output = File(requireNotNull(completed.outputPath))
            val metadata = GeneratedRegions(graph.files).companion(output)
            assertTrue(metadata.isFile)
            graph.repository.reedit(id)
            val draft = requireNotNull(graph.repository.dao.find(id))
            assertEquals(output.absolutePath, draft.basePath)
            assertEquals(RestorationSettings(), draft.settings.restoration)
            assertTrue(metadata.isFile)
            assertEquals(digest, sha256(source))
            // Retain the URI for test cleanup after reedit intentionally clears the gallery reference.
            completed.galleryUri?.let { context.contentResolver.delete(Uri.parse(it), null, null) }
        } finally {
            graph.repository.dao.find(id)?.galleryUri?.let { runCatching { context.contentResolver.delete(Uri.parse(it), null, null) } }
            graph.repository.dao.delete(id)
            graph.files.owned(File(graph.files.root, id)).deleteRecursively()
        }
    }

    private fun portrait(size: Int): Bitmap {
        val raw = InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/astronaut.png").use {
            requireNotNull(BitmapFactory.decodeStream(it))
        }
        val scaled = Bitmap.createScaledBitmap(raw, size, size, true)
        val copy = scaled.copy(Bitmap.Config.ARGB_8888, true)
        if (scaled !== raw) scaled.recycle()
        raw.recycle()
        return copy
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun saveEvidence(bitmap: Bitmap, name: String) {
        val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun sha256(file: File): String = file.inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val read = stream.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
