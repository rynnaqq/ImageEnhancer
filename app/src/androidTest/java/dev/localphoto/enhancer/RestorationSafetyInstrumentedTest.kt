package dev.localphoto.enhancer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphoto.core.Adjustments
import dev.localphoto.core.EnhanceSettings
import dev.localphoto.core.ProjectStatus
import dev.localphoto.core.RestorationSettings
import dev.localphoto.enhancer.ai.GeneratedRegions
import dev.localphoto.enhancer.ai.ModelManager
import dev.localphoto.enhancer.data.PhotoProject
import dev.localphoto.enhancer.data.SettingsCodec
import dev.localphoto.enhancer.processing.ProcessingControl
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RestorationSafetyInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = (context.applicationContext as EnhancerApp).graph

    @Test fun resumedAndReeditedFacePassesRetainReconstructedRegionProtection() = runBlocking {
        val id = "test-${UUID.randomUUID()}"
        try {
            val project = checkpointProject(id, includeRegions = true)
            val originalHash = sha256(File(project.sourcePath))
            graph.repository.dao.insert(project)
            graph.engine.process(project, ProcessingControl())
            val completed = requireNotNull(graph.repository.dao.find(id))
            assertEquals("Resume failed: ${completed.errorCode}", ProjectStatus.COMPLETED, completed.state)
            assertEquals("face_protected_regions", completed.noticeCode)
            assertEquals(originalHash, sha256(File(project.sourcePath)))
            assertProtectedOutput(completed)

            // Re-edit must read protection from the previous result as well as from checkpoints.
            completed.galleryUri?.let { context.contentResolver.delete(android.net.Uri.parse(it), null, null) }
            graph.repository.reedit(id)
            val draft = requireNotNull(graph.repository.dao.find(id))
            val secondPass = draft.copy(status = ProjectStatus.PROCESSING.name,
                settingsJson = SettingsCodec.encode(draft.settings.copy(auto = false,
                    adjustments = Adjustments(scale = 1, sharpen = 0f),
                    restoration = RestorationSettings(faceStrength = 60f))))
            graph.repository.dao.update(secondPass)
            graph.engine.process(secondPass, ProcessingControl())
            val again = requireNotNull(graph.repository.dao.find(id))
            assertEquals("Re-edit failed: ${again.errorCode}", ProjectStatus.COMPLETED, again.state)
            assertEquals("face_protected_regions", again.noticeCode)
            assertEquals(originalHash, sha256(File(project.sourcePath)))
            assertProtectedOutput(again)
        } finally { clean(id) }
    }

    @Test fun repairedCheckpointWithoutProtectionFailsBeforePublishingAResult() = runBlocking {
        val id = "test-${UUID.randomUUID()}"
        try {
            val project = checkpointProject(id, includeRegions = false)
            val originalHash = sha256(File(project.sourcePath))
            graph.repository.dao.insert(project)
            graph.engine.process(project, ProcessingControl())
            val failed = requireNotNull(graph.repository.dao.find(id))
            assertEquals(ProjectStatus.FAILED, failed.state)
            assertEquals("resume_regions", failed.errorCode)
            assertNull(failed.outputPath)
            assertNull(failed.galleryUri)
            assertEquals(originalHash, sha256(File(project.sourcePath)))
        } finally { clean(id) }
    }

    @Test fun stoppingBoundRunOptionsTerminatesActiveNativeRepairInference() {
        val manager = ModelManager(context)
        val control = ProcessingControl()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        // The native worker owns all resources, including on a termination timeout.
        val worker = Thread {
            try {
                manager.openModuleSession("repair-lama", control).use { handle ->
                    OnnxTensor.createTensor(manager.environment, FloatBuffer.wrap(FloatArray(3 * 512 * 512)),
                        longArrayOf(1, 3, 512, 512)).use { image ->
                        OnnxTensor.createTensor(manager.environment, FloatBuffer.wrap(FloatArray(512 * 512)),
                            longArrayOf(1, 1, 512, 512)).use { mask ->
                            OrtSession.RunOptions().use { options ->
                                control.bind(options)
                                try {
                                    started.countDown()
                                    handle.session.run(mapOf("image" to image, "mask" to mask), options).use { }
                                } finally { control.release(options) }
                            }
                        }
                    }
                }
            } catch (caught: Throwable) { failure.set(caught) }
            finally { finished.countDown() }
        }.apply { isDaemon = true }
        worker.start()
        try {
            assertTrue("Native worker never started: ${failure.get()}", started.await(60, TimeUnit.SECONDS))
            // This graph takes multiple seconds; require it to still be running before stopping.
            Thread.sleep(250)
            assertEquals("Inference finished before the cancellation check", 1L, finished.count)
            control.stop()
            assertTrue("Native inference ignored termination", finished.await(60, TimeUnit.SECONDS))
            val caught = failure.get()
            assertTrue("Expected native termination, got $caught", caught is OrtException &&
                caught.message.orEmpty().contains("terminate", ignoreCase = true))
        } finally {
            control.stop()
            worker.join(60_000)
        }
        // A cancelled native session must release its lease and resources for the next tool.
        manager.openModuleSession("yunet").use { assertTrue(it.session.inputNames.isNotEmpty()) }
    }

    private fun checkpointProject(id: String, includeRegions: Boolean): PhotoProject {
        val source = File(graph.files.directory(id), "original.png")
        val checkpoint = File(graph.files.directory(id), "temporary/stage-0.png")
        val raw = InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/astronaut.png").use {
            requireNotNull(BitmapFactory.decodeStream(it))
        }
        val image = Bitmap.createScaledBitmap(raw, 256, 256, true)
        try {
            graph.files.atomicPng(image, source)
            graph.files.atomicPng(image, checkpoint)
        } finally { if (image !== raw) image.recycle(); raw.recycle() }
        if (includeRegions) {
            val protection = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            try { GeneratedRegions(graph.files).write(checkpoint, protection) }
            finally { protection.recycle() }
        }
        val settings = EnhanceSettings(auto = false, adjustments = Adjustments(scale = 1, sharpen = 0f),
            restoration = RestorationSettings(faceStrength = 60f, scratchRepair = true))
        val plan = JSONObject().apply {
            put("settings", JSONObject(SettingsCodec.encode(settings)))
            put("stages", JSONArray(listOf("REPAIR", "FACE_RESTORE")))
            put("detected", JSONArray())
        }
        return PhotoProject(id, "protected-resume.png", source.absolutePath, 256, 256,
            status = ProjectStatus.PROCESSING.name, settingsJson = SettingsCodec.encode(settings),
            checkpointPath = checkpoint.absolutePath, checkpointStage = 0, planJson = plan.toString())
    }

    private fun assertProtectedOutput(project: PhotoProject) {
        val result = File(requireNotNull(project.outputPath))
        val protection = requireNotNull(GeneratedRegions(graph.files).read(result))
        try {
            val pixels = IntArray(protection.width * protection.height)
            protection.getPixels(pixels, 0, protection.width, 0, 0, protection.width, protection.height)
            assertTrue(pixels.all { it == Color.WHITE })
        } finally { protection.recycle() }
    }

    private suspend fun clean(id: String) {
        graph.repository.dao.find(id)?.galleryUri?.let {
            runCatching { context.contentResolver.delete(android.net.Uri.parse(it), null, null) }
        }
        graph.repository.dao.delete(id)
        graph.files.owned(File(graph.files.root, id)).deleteRecursively()
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65_536)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
