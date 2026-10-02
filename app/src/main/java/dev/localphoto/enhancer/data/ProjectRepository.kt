package dev.localphoto.enhancer.data

import android.net.Uri
import dev.localphoto.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID

data class ImportResult(val projects: List<PhotoProject>, val errors: List<String>)

class ProjectRepository(val dao: ProjectDao, val files: ImageFiles) {
    val projects: Flow<List<PhotoProject>> = dao.observeAll()
    private val fileMutations = Mutex()

    suspend fun import(uris: List<Uri>, defaults: EnhanceSettings): ImportResult = withContext(Dispatchers.IO) {
        val imported = mutableListOf<PhotoProject>()
        val errors = mutableListOf<String>()
        for (uri in uris.distinct()) {
            val id = UUID.randomUUID().toString()
            try {
                val (file, name, dimensions) = files.import(uri, id)
                val project = PhotoProject(id, name, file.absolutePath, dimensions.first, dimensions.second,
                    settingsJson = SettingsCodec.encode(defaults))
                dao.insert(project)
                imported += project
            } catch (e: PhotoFailure) { errors += e.code }
            catch (_: Exception) { files.directory(id).deleteRecursively(); errors += "decode" }
        }
        ImportResult(imported, errors)
    }

    suspend fun saveSettings(id: String, settings: EnhanceSettings) {
        val project = dao.find(id) ?: return
        if (!project.editable) return
        val encoded = SettingsCodec.encode(settings)
        if (encoded == project.settingsJson) return
        // A checkpoint belongs to its recipe. Invalidate it atomically, then defer
        // physical cleanup until enqueue so a late autosave cannot delete new work.
        dao.saveEditableSettings(id, encoded, System.currentTimeMillis())
    }

    suspend fun enqueue(ids: List<String>) = fileMutations.withLock {
        val selected = ids.distinct().mapNotNull { dao.find(it) }
        val resumableBatch = selected.takeIf { list -> list.isNotEmpty() && list.all {
            it.state in setOf(ProjectStatus.QUEUED, ProjectStatus.INTERRUPTED) } }?.map { it.batchId }?.distinct()?.singleOrNull()
        val batchId = resumableBatch ?: UUID.randomUUID().toString()
        for (id in ids.distinct()) {
            val project = dao.find(id) ?: continue
            if (!project.editable || project.isReference) continue
            // An interrupted project retains its last completed stage for safe resume.
            val resume = project.state == ProjectStatus.INTERRUPTED
            if (!resume) files.removeTemporary(id)
            dao.enqueueEditable(id, batchId, System.currentTimeMillis())
        }
    }

    suspend fun attachReferences(primary: String, referenceIds: List<String>) = fileMutations.withLock {
        val project = dao.find(primary) ?: return@withLock
        require(project.editable)
        val paths = JSONArray()
        for (id in referenceIds.filter { it != primary }.distinct()) {
            val reference = dao.find(id) ?: continue
            val source = files.owned(File(reference.sourcePath))
            val copy = File(files.directory(primary), "references/$id.img")
            files.owned(copy)
            if (!copy.exists()) {
                if (files.availableBytes() < source.length() + 32L * 1024 * 1024) throw PhotoFailure("storage")
                copy.parentFile?.mkdirs()
                val partial = File(copy.parentFile, copy.name + ".part")
                try {
                    source.copyTo(partial, overwrite = true)
                    if (!partial.renameTo(copy)) throw PhotoFailure("storage")
                } finally { partial.delete() }
            }
            paths.put(copy.absolutePath)
        }
        dao.setEditableReferences(primary, paths.toString(), System.currentTimeMillis())
    }

    suspend fun reedit(id: String) = fileMutations.withLock {
        val project = dao.find(id) ?: return@withLock
        if (!project.editable || project.outputPath == null) return@withLock
        files.removeTemporary(id)
        dao.update(project.copy(status = ProjectStatus.DRAFT.name, basePath = project.outputPath,
            baseWidth = project.outputWidth, baseHeight = project.outputHeight, outputPath = null, galleryUri = null,
            settingsJson = SettingsCodec.encode(project.settings.copy(auto = false,
                adjustments = Adjustments(scale = 1, sharpen = 0f), transform = TransformSettings(),
                restoration = RestorationSettings())),
            checkpointPath = null, checkpointStage = -1, planJson = "", progress = 0f, errorCode = null,
            updatedAt = System.currentTimeMillis()))
    }

    suspend fun removeSettledTemporary(expected: PhotoProject) = withContext(Dispatchers.IO) {
        fileMutations.withLock {
            if (expected.state !in setOf(ProjectStatus.COMPLETED, ProjectStatus.FAILED, ProjectStatus.CANCELLED)) return@withLock
            val current = dao.find(expected.id) ?: return@withLock
            if (current.state == expected.state && current.revision == expected.revision && current.outputPath == expected.outputPath) {
                // Enqueue/re-edit use this lock too. A completed old worker must not
                // delete the checkpoints of a new revision while its export settles.
                files.removeTemporary(expected.id)
            }
        }
    }

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) { fileMutations.withLock {
        val project = dao.find(id) ?: return@withLock
        require(project.editable)
        // References use their own immutable private copies; delete the owning project only.
        files.owned(File(files.root, id)).deleteRecursively()
        dao.delete(id)
    } }
}
