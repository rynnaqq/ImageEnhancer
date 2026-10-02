package dev.localphoto.enhancer

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphoto.core.EnhanceSettings
import dev.localphoto.core.ProjectStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackgroundProcessingInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun autoEnhancementFinishesInBackgroundAndHistoryCanReeditAfterRecreation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = (context.applicationContext as EnhancerApp).graph
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
                .use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        }
        val fixture = File(context.cacheDir, "background-${UUID.randomUUID()}.png")
        val pixels = IntArray(320 * 240) { index ->
            val x = index % 320; val y = index / 320
            val r = (20 + x / 5 + (y % 7)).coerceAtMost(255)
            val g = (35 + y / 4 + (x % 11)).coerceAtMost(255)
            val b = (30 + (x + y) / 8).coerceAtMost(255)
            (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        Bitmap.createBitmap(pixels, 320, 240, Bitmap.Config.ARGB_8888).let { bitmap ->
            fixture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        val originalHash = sha(fixture)
        val imported = graph.repository.import(listOf(Uri.fromFile(fixture)), EnhanceSettings())
        assertTrue(imported.errors.isEmpty())
        val project = imported.projects.single().copy(name = "Background test photo")
        graph.repository.dao.update(project)
        var gallery: Uri? = null
        try {
            compose.runOnIdle { ViewModelProvider(compose.activity)[AppViewModel::class.java].openProject(project.id) }
            compose.waitUntil(20_000) { compose.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Enhance photo"))
            compose.onNodeWithText("Enhance photo").performClick()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            val completed = withTimeout(180_000) {
                graph.repository.projects.first { list -> list.any { item -> item.id == project.id &&
                    (item.state == ProjectStatus.FAILED || (item.state == ProjectStatus.COMPLETED &&
                        (item.galleryUri != null || item.noticeCode == "gallery_save_failed"))) } }
                    .single { it.id == project.id }
            }
            assertEquals("Background job error: ${completed.errorCode}", ProjectStatus.COMPLETED, completed.state)
            assertEquals(640, completed.outputWidth); assertEquals(480, completed.outputHeight)
            assertNotNull(completed.outputPath); assertTrue(File(completed.outputPath!!).isFile)
            assertNotNull(completed.galleryUri); gallery = Uri.parse(completed.galleryUri)
            assertEquals(originalHash, sha(fixture)); assertEquals(originalHash, sha(File(project.sourcePath)))
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.activityRule.scenario.recreate()
            compose.runOnIdle {
                val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
                assertEquals(Screen.QUEUE.name, vm.screen.value)
                vm.navigate(Screen.HISTORY)
            }
            compose.onNodeWithText(project.name).performScrollTo().performClick()
            compose.waitUntil(20_000) { compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Edit again"))
            compose.onNodeWithText("Edit again").performClick()
            compose.waitUntil(15_000) {
                runBlocking { graph.repository.dao.find(project.id)?.state == ProjectStatus.DRAFT }
            }
            val draft = graph.repository.dao.find(project.id)!!
            assertEquals(completed.outputPath, draft.basePath); assertNull(draft.outputPath)
            assertEquals(640, draft.inputWidth); assertEquals(480, draft.inputHeight)
            assertTrue(File(completed.outputPath!!).isFile)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Enhance photo"))
            compose.onNodeWithText("Enhance photo").assertIsDisplayed()
            Unit
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle { ViewModelProvider(compose.activity)[AppViewModel::class.java].navigate(Screen.HOME) }
            (gallery ?: graph.repository.dao.find(project.id)?.galleryUri?.let(Uri::parse))
                ?.let { context.contentResolver.delete(it, null, null) }
            graph.repository.dao.delete(project.id)
            graph.files.directory(project.id).deleteRecursively(); fixture.delete()
        }
    }

    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
