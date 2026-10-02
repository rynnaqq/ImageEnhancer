package dev.localphoto.enhancer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localphoto.enhancer.*
import dev.localphoto.enhancer.R
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun EnhancerRoot(vm: AppViewModel) {
    val screenName by vm.screen.collectAsStateWithLifecycle()
    val screen = Screen.valueOf(screenName)
    val projects by vm.projects.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val loadedId by vm.loadedProjectId.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { vm.importPhotos(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importPhotos(it) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val addPhotos = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val browseFiles = { filePicker.launch(arrayOf("image/*")) }
    fun ensureNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(message) { message?.let { snackbars.showSnackbar(context.getString(messageLabel(it))); vm.consumeMessage() } }
    LaunchedEffect(draft, loadedId) { if (loadedId == selectedId && loadedId != null) { delay(400); vm.saveDraft() } }
    BackHandler(enabled = screen != Screen.HOME) { if (screen == Screen.EDITOR) vm.saveDraft(); vm.navigate(Screen.HOME) }
    val project = projects.firstOrNull { it.id == selectedId }
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(if (screen == Screen.EDITOR && project != null) project.name else stringResource(when (screen) {
                    Screen.HOME -> R.string.app_name; Screen.HISTORY -> R.string.history; Screen.PROJECTS -> R.string.projects
                    Screen.QUEUE -> R.string.queue; Screen.SETTINGS -> R.string.settings
                    Screen.IMPORT -> R.string.select_mode; Screen.EDITOR -> R.string.editor
                }), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            }, navigationIcon = {
                if (screen != Screen.HOME) IconButton(onClick = { if (screen == Screen.EDITOR) vm.saveDraft(); vm.navigate(Screen.HOME) }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                }
            }, actions = {
                if (screen != Screen.SETTINGS) IconButton(onClick = { if (screen == Screen.EDITOR) vm.saveDraft(); vm.navigate(Screen.SETTINGS) }) {
                    Icon(Icons.Outlined.Settings, stringResource(R.string.settings))
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
        bottomBar = {
            if (screen !in setOf(Screen.EDITOR, Screen.IMPORT, Screen.SETTINGS)) NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                listOf(Triple(Screen.HOME, R.string.home, Icons.Outlined.Home), Triple(Screen.HISTORY, R.string.history, Icons.Outlined.Collections),
                    Triple(Screen.PROJECTS, R.string.projects, Icons.Outlined.FolderOpen), Triple(Screen.QUEUE, R.string.queue, Icons.Outlined.HourglassEmpty)).forEach { (destination, title, icon) ->
                    NavigationBarItem(selected = screen == destination, onClick = { vm.navigate(destination) },
                        icon = { Icon(icon, null) }, label = { Text(stringResource(title)) })
                }
            }
        }, snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.HOME -> HomeScreen(vm, projects, addPhotos, browseFiles)
                Screen.IMPORT -> ImportModeScreen(vm, projects) { ids -> ensureNotifications(); vm.startBatch(ids) }
                Screen.EDITOR -> if (project != null && loadedId == project.id) {
                    EditorScreen(vm, project, draft) { ensureNotifications(); vm.enhanceCurrent() }
                } else CircularProgressIndicator(Modifier.align(Alignment.Center))
                Screen.QUEUE -> QueueScreen(vm, projects) { ensureNotifications(); vm.resumeQueue() }
                Screen.HISTORY -> LibraryScreen(vm, projects.filter { it.state != dev.localphoto.core.ProjectStatus.DRAFT }, false)
                Screen.PROJECTS -> LibraryScreen(vm, projects, true)
                Screen.SETTINGS -> SettingsScreen(vm)
            }
            if (busy) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background.copy(alpha = 0.9f)) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(); Spacer(Modifier.height(20.dp)); Text(stringResource(R.string.preparing))
                }
            }
        }
    }
}
