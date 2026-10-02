package dev.localphoto.enhancer

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphoto.core.EnhanceSettings
import dev.localphoto.core.ProjectStatus
import dev.localphoto.enhancer.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ConcurrencyInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun delayedAutosaveCannotRevertAnAlreadyQueuedProjectToDraft() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ProjectDatabase::class.java).build()
        val files = ImageFiles(context)
        val id = UUID.randomUUID().toString()
        val project = PhotoProject(id, "Photo", File(files.directory(id), "original.png").absolutePath, 20, 20)
        db.projects().insert(project)
        val barrier = DelayedReadDao(db.projects(), pauseOnRead = 1)
        val repo = ProjectRepository(barrier, files)
        val changed = project.settings.copy(auto = false)
        val saving = async(Dispatchers.Default) { repo.saveSettings(id, changed) }
        try {
            withTimeout(5000) { barrier.readCaptured.await() }
            repo.enqueue(listOf(id))
            barrier.release.complete(Unit); saving.await()
            assertEquals(ProjectStatus.QUEUED, db.projects().find(id)!!.state)
        } finally { barrier.release.complete(Unit); saving.join(); files.directory(id).deleteRecursively(); db.close() }
    }

    @Test fun delayedEnqueueCannotReplaceNewerRecipeWithItsOldSnapshot() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ProjectDatabase::class.java).build()
        val files = ImageFiles(context)
        val id = UUID.randomUUID().toString()
        val project = PhotoProject(id, "Photo", File(files.directory(id), "original.png").absolutePath, 20, 20)
        db.projects().insert(project)
        val barrier = DelayedReadDao(db.projects(), pauseOnRead = 2)
        val repo = ProjectRepository(barrier, files)
        val changed = EnhanceSettings(auto = false, adjustments = project.settings.adjustments.copy(scale = 4, exposure = 0.75f))
        val queuing = async(Dispatchers.Default) { repo.enqueue(listOf(id)) }
        try {
            withTimeout(5000) { barrier.readCaptured.await() }
            repo.saveSettings(id, changed)
            barrier.release.complete(Unit); queuing.await()
            val queued = db.projects().find(id)!!
            assertEquals(ProjectStatus.QUEUED, queued.state); assertEquals(changed, queued.settings)
        } finally { barrier.release.complete(Unit); queuing.join(); files.directory(id).deleteRecursively(); db.close() }
    }

    @Test fun lateGalleryWritesCannotRestoreAReeditedOrRequeuedRevision() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ProjectDatabase::class.java).build()
        val files = ImageFiles(context)
        val id = UUID.randomUUID().toString()
        val result = File(files.directory(id), "results/result-1.png")
        val dao = db.projects()
        val completed = PhotoProject(id, "Photo", File(files.directory(id), "original.png").absolutePath, 20, 20,
            status = ProjectStatus.COMPLETED.name, outputPath = result.absolutePath,
            outputWidth = 40, outputHeight = 40, revision = 1, noticeCode = "source_sampled")
        dao.insert(completed)
        val repo = ProjectRepository(dao, files)
        try {
            assertEquals(1, dao.recordGalleryExport(id, 1, result.absolutePath, "content://test/first"))
            assertEquals("source_sampled", dao.find(id)!!.noticeCode)
            repo.reedit(id)
            assertEquals(0, dao.recordGalleryExport(id, 1, result.absolutePath, "content://test/late"))
            assertEquals(0, dao.recordGalleryFailure(id, 1, result.absolutePath))
            val draft = dao.find(id)!!
            assertEquals(ProjectStatus.DRAFT, draft.state)
            assertEquals(result.absolutePath, draft.basePath); assertNull(draft.outputPath); assertNull(draft.galleryUri)
            assertFalse(draft.settings.auto)
            repo.enqueue(listOf(id))
            assertEquals(0, dao.recordGalleryFailure(id, 1, result.absolutePath))
            assertEquals(0, dao.recordGalleryExport(id, 1, result.absolutePath, "content://test/late"))
            assertEquals(ProjectStatus.QUEUED, dao.find(id)!!.state)
            // Deleting an older copy must not clear a newer successful save.
            dao.update(completed)
            dao.recordGalleryExport(id, 1, result.absolutePath, "content://test/new")
            assertEquals(0, dao.clearGalleryExport(id, 1, result.absolutePath, "content://test/first"))
            assertEquals("content://test/new", dao.find(id)!!.galleryUri)
            assertEquals(0, dao.recordGalleryExport(id, 0, result.absolutePath, "content://test/wrong-revision"))
        } finally { files.directory(id).deleteRecursively(); db.close() }
    }

    @Test fun cancelledQueueSnapshotCannotBeClaimedAndActiveItemCannotBeReclaimed() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ProjectDatabase::class.java).build()
        val id = UUID.randomUUID().toString()
        val dao = db.projects()
        dao.insert(PhotoProject(id, "Photo", "/private/original.png", 20, 20, status = ProjectStatus.QUEUED.name))
        try {
            val snapshot = dao.nextQueued()!!
            dao.cancelQueued()
            assertEquals(0, dao.claimQueued(snapshot.id))
            assertEquals(ProjectStatus.CANCELLED, dao.find(id)!!.state)
            assertEquals(1, dao.enqueueEditable(id, "batch", System.currentTimeMillis()))
            assertEquals(1, dao.claimQueued(id))
            assertEquals(0, dao.claimQueued(id))
            dao.cancelQueued()
            assertEquals(ProjectStatus.PROCESSING, dao.find(id)!!.state)
        } finally { db.close() }
    }

    @Test fun lateCompletedCleanupCannotDeleteNewRevisionCheckpoints() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ProjectDatabase::class.java).build()
        val files = ImageFiles(context)
        val id = UUID.randomUUID().toString()
        val result = File(files.directory(id), "results/result-1.png")
        val completed = PhotoProject(id, "Photo", File(files.directory(id), "original.png").absolutePath, 20, 20,
            status = ProjectStatus.COMPLETED.name, outputPath = result.absolutePath,
            outputWidth = 40, outputHeight = 40, revision = 1)
        val dao = db.projects()
        val repo = ProjectRepository(dao, files)
        dao.insert(completed)
        val checkpoint = File(files.directory(id), "temporary/stage-0.png")
        fun writeCheckpoint() { checkpoint.parentFile!!.mkdirs(); checkpoint.writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            writeCheckpoint()
            repo.removeSettledTemporary(completed)
            assertFalse(checkpoint.exists())
            repo.reedit(id); repo.enqueue(listOf(id)); assertEquals(1, dao.claimQueued(id))
            writeCheckpoint()
            val processing = dao.find(id)!!.copy(checkpointPath = checkpoint.absolutePath, checkpointStage = 0)
            dao.update(processing)
            repo.removeSettledTemporary(completed)
            assertTrue(checkpoint.exists())
            assertEquals(processing, dao.find(id))
        } finally { files.directory(id).deleteRecursively(); db.close() }
    }

    // Real Room writes are unchanged. Delay delivery of one completed read to
    // reproduce a valid slow-device interleaving without timing-dependent sleeps.
    private class DelayedReadDao(private val delegate: ProjectDao, private val pauseOnRead: Int) : ProjectDao by delegate {
        private val reads = AtomicInteger()
        val readCaptured = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun find(id: String): PhotoProject? {
            val snapshot = delegate.find(id)
            if (reads.incrementAndGet() == pauseOnRead) { readCaptured.complete(Unit); release.await() }
            return snapshot
        }
    }
}
