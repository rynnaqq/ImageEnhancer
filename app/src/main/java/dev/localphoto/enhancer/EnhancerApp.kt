package dev.localphoto.enhancer

import android.app.Application
import dev.localphoto.enhancer.ai.*
import dev.localphoto.enhancer.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class EnhancerApp : Application() {
    lateinit var graph: AppGraph
        private set
    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.scope.launch {
            graph.repository.dao.recoverInterrupted()
            graph.recovered.value = true
        }
    }
}

class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val recovered = MutableStateFlow(false)
    val database = ProjectDatabase.open(app)
    val files = ImageFiles(app)
    val repository = ProjectRepository(database.projects(), files)
    val settings = SettingsStore(app)
    val models = ModelManager(app)
    val exporter = GalleryExporter(app, files)
    val engine = EnhancementEngine(app, repository, models, exporter)
}
