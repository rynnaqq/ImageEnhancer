package dev.localphoto.enhancer

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphoto.core.Adjustments
import dev.localphoto.core.EnhanceSettings
import dev.localphoto.core.ProjectStatus
import dev.localphoto.enhancer.ai.ModelManager
import dev.localphoto.enhancer.ai.TiledSuperResolution
import dev.localphoto.enhancer.data.PhotoProject
import dev.localphoto.enhancer.data.SettingsCodec
import dev.localphoto.enhancer.processing.ProcessingControl
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EngineInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun modelPreparationRepairsCorruptPrivateCopyAndVerifiesChecksum() {
        val manifest = JSONObject(
            context.assets.open("models/manifest.json").bufferedReader().use { it.readText() },
        ).getJSONArray("models").getJSONObject(0)
        val destination = File(context.filesDir, "models/espcn-x3.onnx")
        destination.parentFile?.mkdirs()
        destination.writeBytes(byteArrayOf(1, 2, 3, 4))

        val prepared = ModelManager(context).prepare()

        assertEquals(manifest.getLong("bytes"), prepared.length())
        assertEquals(manifest.getString("sha256"), sha256(prepared))
    }

    @Test
    fun realInferenceHasRequestedDimensionsAndDiffersFromInterpolation() = runBlocking {
        val source = fixtureBitmap(24, 20, seed = 7)
        val progress = mutableListOf<Float>()
        val output = try {
            TiledSuperResolution(ModelManager(context)).upscale(
                source = source,
                scale = 2,
                tileSize = 32,
                control = ProcessingControl(),
            ) { progress += it }
        } finally {
            source.recycle()
        }

        try {
            assertEquals(48, output.width)
            assertEquals(40, output.height)
            assertTrue(progress.isNotEmpty())
            assertEquals(1f, progress.last(), 0.0001f)

            val original = fixtureBitmap(24, 20, seed = 7)
            val interpolated = Bitmap.createScaledBitmap(original, output.width, output.height, true)
            original.recycle()
            try {
                val actual = IntArray(output.width * output.height)
                val baseline = IntArray(actual.size)
                output.getPixels(actual, 0, output.width, 0, 0, output.width, output.height)
                interpolated.getPixels(baseline, 0, output.width, 0, 0, output.width, output.height)
                assertTrue(actual.all { pixel -> pixel ushr 24 == 0xff })
                assertTrue(
                    "learned luminance output must not collapse to local interpolation",
                    actual.indices.count { actual[it] != baseline[it] } > actual.size / 20,
                )
            } finally {
                interpolated.recycle()
            }
        } finally {
            output.recycle()
        }
    }

    @Test
    fun thinImageUsesReflectionPaddingAndKeepsExactFourXDimensions() = runBlocking {
        val source = fixtureBitmap(1, 7, seed = 23)
        val progress = mutableListOf<Float>()
        val output = try {
            TiledSuperResolution(ModelManager(context)).upscale(
                source = source,
                scale = 4,
                tileSize = 32,
                control = ProcessingControl(),
            ) { progress += it }
        } finally {
            source.recycle()
        }

        try {
            assertEquals(4, output.width)
            assertEquals(28, output.height)
            assertTrue(progress.isNotEmpty())
            assertEquals(1f, progress.last(), 0.0001f)
            assertBitmapIsFullyOpaque(output)
        } finally {
            output.recycle()
        }
    }

    @Test
    fun partialEdgeTileCompletesMultipleTilesAtExactEightXDimensions() = runBlocking {
        // A 224 tile with 8-pixel halos has a 208-pixel core. The final source column
        // therefore exercises a second, narrow edge tile and its asymmetric padding crop.
        val source = fixtureBitmap(209, 9, seed = 29)
        val progress = mutableListOf<Float>()
        val output = try {
            TiledSuperResolution(ModelManager(context)).upscale(
                source = source,
                scale = 8,
                tileSize = 512,
                control = ProcessingControl(),
            ) { progress += it }
        } finally {
            source.recycle()
        }

        try {
            assertEquals(1672, output.width)
            assertEquals(72, output.height)
            assertTrue(progress.size >= 2)
            assertEquals(listOf(0.5f, 1f), progress.takeLast(2))
            assertEquals(1f, progress.last(), 0.0001f)
            assertBitmapIsFullyOpaque(output)
        } finally {
            output.recycle()
        }
    }

    @Test
    fun cancelledItemDoesNotPreventNextQueuedProjectCompleting() = runBlocking {
        val app = context.applicationContext as EnhancerApp
        val graph = app.graph
        val firstId = "test-${UUID.randomUUID()}"
        val secondId = "test-${UUID.randomUUID()}"
        try {
            val first = insertFixtureProject(graph, firstId, "cancelled.png", seed = 11)
            val second = insertFixtureProject(graph, secondId, "continued.png", seed = 19)

            val firstControl = ProcessingControl().apply { stop() }
            graph.repository.dao.update(first.copy(status = ProjectStatus.PROCESSING.name))
            graph.engine.process(first.copy(status = ProjectStatus.PROCESSING.name), firstControl)

            assertEquals(ProjectStatus.CANCELLED, graph.repository.dao.find(firstId)?.state)
            assertEquals(secondId, graph.repository.dao.nextQueued()?.id)

            graph.repository.dao.update(second.copy(status = ProjectStatus.PROCESSING.name))
            graph.engine.process(second.copy(status = ProjectStatus.PROCESSING.name), ProcessingControl())

            val completed = graph.repository.dao.find(secondId)
            assertNotNull(completed)
            assertEquals(ProjectStatus.COMPLETED, completed?.state)
            assertEquals(32, completed?.outputWidth)
            assertEquals(24, completed?.outputHeight)
            assertTrue(completed?.outputPath?.let { File(it) }?.isFile == true)
            assertFalse(completed?.galleryUri.isNullOrBlank())
        } finally {
            cleanupProject(app, firstId)
            cleanupProject(app, secondId)
        }
    }

    private suspend fun insertFixtureProject(
        graph: AppGraph,
        id: String,
        name: String,
        seed: Int,
    ): PhotoProject {
        val source = File(graph.files.directory(id), "original.png")
        val bitmap = fixtureBitmap(32, 24, seed)
        try {
            graph.files.atomicPng(bitmap, source)
        } finally {
            bitmap.recycle()
        }
        val settings = EnhanceSettings(
            auto = false,
            adjustments = Adjustments(scale = 1, sharpen = 0f),
        )
        val project = PhotoProject(
            id = id,
            name = name,
            sourcePath = source.absolutePath,
            width = 32,
            height = 24,
            status = ProjectStatus.QUEUED.name,
            settingsJson = SettingsCodec.encode(settings),
        )
        graph.repository.dao.insert(project)
        return project
    }

    private suspend fun cleanupProject(app: EnhancerApp, id: String) {
        val project = app.graph.repository.dao.find(id)
        project?.galleryUri?.let { uri -> runCatching { context.contentResolver.delete(android.net.Uri.parse(uri), null, null) } }
        app.graph.repository.dao.delete(id)
        runCatching { app.graph.files.owned(File(app.graph.files.root, id)).deleteRecursively() }
    }

    private fun fixtureBitmap(width: Int, height: Int, seed: Int): Bitmap {
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val red = (x * 17 + y * 3 + seed * 13) and 0xff
            val green = (x * 5 + y * 19 + seed * 7) and 0xff
            val blue = ((x xor y) * 23 + seed * 11) and 0xff
            (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun assertBitmapIsFullyOpaque(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue(pixels.all { pixel -> pixel ushr 24 == 0xff })
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
