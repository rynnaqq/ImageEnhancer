package dev.localphoto.enhancer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphoto.core.*
import dev.localphoto.enhancer.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProjectInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun applicationCannotAccessInternet() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions?.contains(Manifest.permission.INTERNET) == true)
    }

    @Test fun settingsRoundTripPreservesManualEditsAndOutputChoices() {
        val settings = EnhanceSettings(auto = false, profile = Profile.BALANCED,
            adjustments = Adjustments(scale = 4, denoise = 42f, exposure = -1.2f, gamma = 1.25f,
                hue = 50f, temperature = -24f, redBalance = 13f, fadedColor = 31f),
            transform = TransformSettings(rotationDegrees = 90, flipHorizontal = true, cropLeft = 0.2f,
                cropRight = 0.8f, straightenDegrees = 4f), output = OutputSettings(OutputFormat.WEBP, 81, false))
        assertEquals(settings, SettingsCodec.decode(SettingsCodec.encode(settings)))
        assertEquals(EnhanceSettings(), SettingsCodec.decode("broken"))
    }

    @Test fun privateImportCropExportAndDeleteNeverChangeOriginalBytes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ProjectDatabase::class.java).build()
        val files = ImageFiles(context)
        val repository = ProjectRepository(db.projects(), files)
        val fixture = File(context.cacheDir, "fixture-${UUID.randomUUID()}.jpg")
        val original = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff567890.toInt()) }
        fixture.outputStream().use { original.compress(Bitmap.CompressFormat.JPEG, 98, it) }; original.recycle()
        ExifInterface(fixture).apply { setAttribute(ExifInterface.TAG_MAKE, "Private camera"); saveAttributes() }
        val hash = sha(fixture)
        var project: PhotoProject? = null
        val exports = mutableListOf<Uri>()
        try {
            val imported = repository.import(listOf(Uri.fromFile(fixture)), EnhanceSettings())
            assertTrue(imported.errors.isEmpty())
            project = imported.projects.single()
            assertEquals(80, project.width); assertEquals(60, project.height)
            assertEquals(hash, sha(File(project.sourcePath)))
            val decoded = files.decode(File(project.sourcePath), 10_000)
            val cropped = files.transform(decoded, TransformSettings(cropLeft = 0.25f, cropRight = 0.75f, rotationDegrees = 90))
            assertEquals(60, cropped.width); assertEquals(40, cropped.height)
            val exporter = GalleryExporter(context, files)
            for (format in OutputFormat.entries) {
                val uri = exporter.export(project, cropped, OutputSettings(format, 93, true)); exports += uri
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    val result = BitmapFactory.decodeStream(input)
                    assertNotNull(result); assertEquals(60, result.width); assertEquals(40, result.height); result.recycle()
                }
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    assertNull(ExifInterface(input).getAttribute(ExifInterface.TAG_MAKE))
                }
            }
            cropped.recycle(); if (decoded !== cropped) decoded.recycle()
            assertEquals(hash, sha(fixture))
            repository.deleteProject(project.id)
            assertTrue(fixture.exists()); assertEquals(hash, sha(fixture))
            assertNull(db.projects().find(project.id))
        } finally {
            exports.forEach { context.contentResolver.delete(it, null, null) }
            project?.let { files.directory(it.id).deleteRecursively() }
            fixture.delete(); db.close()
        }
    }

    @Test fun corruptPhotoDoesNotRejectValidBatchImportsOrLeaveTempFiles() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ProjectDatabase::class.java).build()
        val files = ImageFiles(context)
        val repository = ProjectRepository(db.projects(), files)
        val bad = File(context.cacheDir, "bad-${UUID.randomUUID()}.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val good = File(context.cacheDir, "good-${UUID.randomUUID()}.png")
        Bitmap.createBitmap(24, 18, Bitmap.Config.ARGB_8888).let { bitmap ->
            good.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        try {
            val result = repository.import(listOf(Uri.fromFile(bad), Uri.fromFile(good)), EnhanceSettings())
            assertEquals(1, result.errors.size); assertEquals("decode", result.errors.single())
            assertEquals(1, result.projects.size); assertEquals(24, result.projects.single().width)
            repository.enqueue(result.projects.map { it.id })
            assertNotNull(db.projects().nextQueued())
            result.projects.forEach { files.directory(it.id).deleteRecursively() }
        } finally { bad.delete(); good.delete(); db.close() }
    }

    @Test fun pathTraversalIsRejectedBeforeWritingOutsidePrivateProjects() {
        val files = ImageFiles(context)
        assertThrows(IllegalArgumentException::class.java) { files.directory("../../outside") }
        assertThrows(IllegalArgumentException::class.java) { files.owned(File(context.cacheDir, "outside.png")) }
    }

    @Test fun reeditingUsesPreviousRevisionAsInputAndStartsWithoutAnOldComparisonResult() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ProjectDatabase::class.java).build()
        val files = ImageFiles(context)
        val repo = ProjectRepository(db.projects(), files)
        val id = UUID.randomUUID().toString()
        val input = File(files.directory(id), "original.png")
        val result = File(files.directory(id), "results/result-1.png")
        Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).let { bitmap ->
            files.atomicPng(bitmap, input); files.atomicPng(bitmap, result); bitmap.recycle()
        }
        db.projects().insert(PhotoProject(id, "Photo.png", input.absolutePath, 40, 30,
            status = ProjectStatus.COMPLETED.name, outputPath = result.absolutePath, outputWidth = 80, outputHeight = 60, revision = 1))
        try {
            repo.reedit(id)
            val draft = db.projects().find(id)!!
            assertEquals(result.absolutePath, draft.basePath)
            assertNull(draft.outputPath)
            assertEquals(1, draft.revision)
            assertTrue(result.isFile)
            assertEquals(ProjectStatus.DRAFT, draft.state)
        } finally { files.directory(id).deleteRecursively(); db.close() }
    }

    @Test fun referenceAssetsBelongToMainProjectAndSurviveDeletingStandaloneReference() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ProjectDatabase::class.java).build()
        val files = ImageFiles(context)
        val repo = ProjectRepository(db.projects(), files)
        val mainId = UUID.randomUUID().toString()
        val refId = UUID.randomUUID().toString()
        fun fixture(id: String): PhotoProject {
            val file = File(files.directory(id), "original.png")
            Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).let { b -> files.atomicPng(b, file); b.recycle() }
            return PhotoProject(id, "Photo.png", file.absolutePath, 20, 20)
        }
        db.projects().insert(fixture(mainId)); db.projects().insert(fixture(refId))
        try {
            repo.attachReferences(mainId, listOf(refId))
            val refs = org.json.JSONArray(db.projects().find(mainId)!!.referencePathsJson)
            val ownedReference = File(refs.getString(0))
            assertTrue(ownedReference.canonicalPath.startsWith(files.directory(mainId).canonicalPath + File.separator))
            repo.deleteProject(refId)
            assertTrue(ownedReference.isFile)
        } finally { files.directory(mainId).deleteRecursively(); files.directory(refId).deleteRecursively(); db.close() }
    }

    @Test fun editingInterruptedRecipeDiscardsOldCheckpointButUnchangedRecipeResumes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ProjectDatabase::class.java).build()
        val files = ImageFiles(context)
        val repo = ProjectRepository(db.projects(), files)
        val id = UUID.randomUUID().toString()
        val settings = EnhanceSettings(auto = false, adjustments = Adjustments(scale = 2, exposure = 1f))
        val checkpoint = File(files.directory(id), "temporary/stage-0.png")
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        files.atomicPng(bitmap, checkpoint); bitmap.recycle()
        val project = PhotoProject(id, "Interrupted.png", File(files.directory(id), "original.png").absolutePath, 20, 20,
            status = ProjectStatus.INTERRUPTED.name, settingsJson = SettingsCodec.encode(settings),
            checkpointPath = checkpoint.absolutePath, checkpointStage = 0, progress = 0.4f,
            planJson = "saved recipe", errorCode = "interrupted")
        db.projects().insert(project)
        try {
            repo.saveSettings(id, settings)
            assertEquals(checkpoint.absolutePath, db.projects().find(id)!!.checkpointPath)
            assertEquals(ProjectStatus.INTERRUPTED, db.projects().find(id)!!.state)
            val changed = settings.copy(adjustments = settings.adjustments.copy(exposure = -1f))
            repo.saveSettings(id, changed)
            val draft = db.projects().find(id)!!
            assertEquals(changed, draft.settings)
            assertEquals(ProjectStatus.DRAFT, draft.state)
            assertNull(draft.checkpointPath); assertEquals(-1, draft.checkpointStage)
            assertEquals("", draft.planJson); assertEquals(0f, draft.progress)
            // Logical invalidation is immediate; enqueue owns physical cleanup.
            assertTrue(checkpoint.exists())
            repo.enqueue(listOf(id))
            assertFalse(checkpoint.exists())
            assertEquals(ProjectStatus.QUEUED, db.projects().find(id)!!.state)
            assertEquals(changed, db.projects().find(id)!!.settings)
        } finally { files.directory(id).deleteRecursively(); db.close() }
    }

    @Test fun sharingCompletedWideImageKeepsEveryOutputPixel() {
        val files = ImageFiles(context)
        val id = UUID.randomUUID().toString()
        val result = File(files.directory(id), "results/result-1.png")
        val bitmap = Bitmap.createBitmap(8200, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff345678.toInt()) }
        files.atomicPng(bitmap, result); bitmap.recycle()
        val project = PhotoProject(id, "Panorama.png", result.absolutePath, 4100, 1,
            status = ProjectStatus.COMPLETED.name, outputPath = result.absolutePath, outputWidth = 8200, outputHeight = 2)
        try {
            val uri = GalleryExporter(context, files).shareCopy(project)
            context.contentResolver.openInputStream(uri)!!.use { stream ->
                val shared = BitmapFactory.decodeStream(stream)
                try { assertEquals(8200, shared.width); assertEquals(2, shared.height) }
                finally { shared.recycle() }
            }
        } finally { files.directory(id).deleteRecursively(); File(context.cacheDir, "share/${id}_enhanced.png").delete() }
    }

    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
