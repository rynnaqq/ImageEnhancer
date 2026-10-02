package dev.localphoto.enhancer

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.localphoto.core.*
import dev.localphoto.enhancer.ai.*
import dev.localphoto.enhancer.data.*
import dev.localphoto.enhancer.processing.EnhancementService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import java.io.File

enum class Screen { HOME, IMPORT, EDITOR, QUEUE, HISTORY, PROJECTS, SETTINGS }

class AppViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    val graph = (application as EnhancerApp).graph
    val projects = graph.repository.projects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val defaults = graph.settings.defaults.stateIn(viewModelScope, SharingStarted.Eagerly, EnhanceSettings())
    val screen = saved.getStateFlow("screen", Screen.HOME.name)
    val selectedId = saved.getStateFlow<String?>("project", null)
    val importIds = saved.getStateFlow("imports", arrayListOf<String>())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val draft = MutableStateFlow(EnhanceSettings())
    val loadedProjectId = MutableStateFlow<String?>(null)
    val metrics = MutableStateFlow<ImageMetrics?>(null)
    val capabilities = MutableStateFlow(DeviceCapabilityDetector.detect(application))
    val modelState = graph.models.state
    val modelCatalog = graph.models.catalog
    private var analysisJob: Job? = null

    init { selectedId.value?.let { openProject(it, showEditor = false) } }

    fun navigate(screen: Screen) { saved["screen"] = screen.name }
    fun consumeMessage() { message.value = null }
    fun importPhotos(uris: List<Uri>) {
        if (uris.isEmpty() || busy.value) return
        viewModelScope.launch {
            busy.value = true
            val result = graph.repository.import(uris, defaults.value)
            busy.value = false
            if (result.errors.isNotEmpty()) message.value = result.errors.first().takeIf { result.projects.isEmpty() } ?: "import_failed"
            if (result.projects.size == 1) openProject(result.projects[0].id)
            else if (result.projects.isNotEmpty()) {
                saved["imports"] = ArrayList(result.projects.map { it.id })
                navigate(Screen.IMPORT)
            }
        }
    }

    fun openProject(id: String, showEditor: Boolean = true) {
        loadedProjectId.value = null
        saved["project"] = id
        if (showEditor) navigate(Screen.EDITOR)
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            val project = graph.repository.dao.find(id) ?: return@launch
            draft.value = project.settings
            loadedProjectId.value = id
            metrics.value = null
            try {
                metrics.value = withContext(Dispatchers.Default) {
                    val bitmap = graph.files.decode(File(project.basePath ?: project.sourcePath), 384L * 384)
                    try {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val dimensions = graph.files.dimensions(File(project.basePath ?: project.sourcePath))
                        ImageAnalyzer.analyze(pixels, bitmap.width, bitmap.height).copy(width = dimensions.first, height = dimensions.second)
                    } finally { bitmap.recycle() }
                }
            } catch (e: PhotoFailure) { message.value = e.code }
            capabilities.value = DeviceCapabilityDetector.detect(getApplication())
        }
    }

    fun updateDraft(settings: EnhanceSettings) { draft.value = settings }
    fun saveDraft(showMessage: Boolean = false) {
        val id = selectedId.value ?: return
        if (loadedProjectId.value != id) return
        val settings = draft.value
        viewModelScope.launch { graph.repository.saveSettings(id, settings); if (showMessage) message.value = "project_saved" }
    }
    fun enhanceCurrent() {
        val id = selectedId.value ?: return
        if (loadedProjectId.value != id) return
        viewModelScope.launch { graph.repository.saveSettings(id, draft.value); start(listOf(id)) }
    }
    fun startBatch(ids: List<String>) { viewModelScope.launch { start(ids) } }
    private suspend fun start(ids: List<String>) {
        graph.repository.enqueue(ids)
        try { EnhancementService.start(getApplication()); navigate(Screen.QUEUE) }
        catch (_: Exception) { message.value = "service" }
    }
    fun referenceProject(primary: String, ids: List<String>) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { graph.repository.attachReferences(primary, ids) }; openProject(primary) }
            catch (e: Exception) { message.value = (e as? PhotoFailure)?.code ?: "import_failed" }
        }
    }
    fun cancel(all: Boolean) {
        val active = projects.value.any { it.state == ProjectStatus.PROCESSING }
        // The flow can lag behind the worker's claim; cancel-all must reach it anyway.
        if (all || active) EnhancementService.cancel(getApplication(), all)
        if (all) viewModelScope.launch { graph.repository.dao.cancelQueued() }
    }
    fun resumeQueue() { startBatch(projects.value.filter { it.state in setOf(ProjectStatus.QUEUED, ProjectStatus.INTERRUPTED) }.map { it.id }) }
    fun editAgain(id: String) { viewModelScope.launch { graph.repository.reedit(id); openProject(id) } }
    fun deleteProject(id: String) {
        viewModelScope.launch {
            try { graph.repository.deleteProject(id); navigate(Screen.PROJECTS) }
            catch (_: Exception) { message.value = "export" }
        }
    }
    fun deleteGalleryCopy(project: PhotoProject) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                project.galleryUri?.let {
                    if (getApplication<Application>().contentResolver.delete(Uri.parse(it), null, null) <= 0) throw PhotoFailure("export")
                }
                if (project.galleryUri != null && project.outputPath != null) {
                    graph.repository.dao.clearGalleryExport(project.id, project.revision, project.outputPath, project.galleryUri)
                }
            }.onFailure { message.value = "export" }
        }
    }
    fun saveCopy(project: PhotoProject) {
        val outputSettings = if (loadedProjectId.value == project.id) draft.value.output else project.settings.output
        viewModelScope.launch {
            busy.value = true
            try {
                withContext(Dispatchers.IO) {
                    val outputPath = project.outputPath ?: throw PhotoFailure("export")
                    val bitmap = graph.files.decodeResult(graph.files.owned(File(outputPath)))
                    try {
                        val uri = graph.exporter.export(project, bitmap, outputSettings)
                        graph.repository.dao.recordGalleryExport(project.id, project.revision, outputPath, uri.toString())
                    } finally { bitmap.recycle() }
                }
                message.value = "saved"
            } catch (e: Exception) { message.value = (e as? PhotoFailure)?.code ?: "export" }
            finally { busy.value = false }
        }
    }
    fun share(project: PhotoProject) {
        viewModelScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) { graph.exporter.shareCopy(project) }
                val intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                getApplication<Application>().startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { message.value = "export" }
        }
    }
    fun updateDefaults(settings: EnhanceSettings) { viewModelScope.launch { graph.settings.save(settings) } }
    fun initializeModels() {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try { withContext(Dispatchers.Default) { graph.models.initializeAll() }; capabilities.value = DeviceCapabilityDetector.detect(getApplication()) }
            catch (e: Exception) { message.value = (e as? PhotoFailure)?.code ?: "model_init" }
            finally { busy.value = false }
        }
    }
}
