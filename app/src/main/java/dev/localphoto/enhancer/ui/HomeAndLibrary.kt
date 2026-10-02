package dev.localphoto.enhancer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localphoto.core.ProjectStatus
import dev.localphoto.enhancer.*
import dev.localphoto.enhancer.R
import dev.localphoto.enhancer.data.PhotoProject
import java.text.DateFormat
import java.util.Date

@Composable fun HomeScreen(vm: AppViewModel, projects: List<PhotoProject>, addPhotos: () -> Unit, browseFiles: () -> Unit) {
    val device by vm.capabilities.collectAsStateWithLifecycle()
    val model by vm.modelState.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PrivacyTip, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.offline_tag), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineLarge)
                Text(stringResource(R.string.home_description), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = addPhotos, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.add_photos))
                }
                TextButton(onClick = browseFiles, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.browse_files))
                }
                Text(stringResource(R.string.import_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    Benefit(Icons.Outlined.Lock, R.string.home_private, R.string.home_private_body)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Benefit(Icons.Outlined.Layers, R.string.home_originals, R.string.home_originals_body)
                }
            }
        }
        item {
            if (!model.ready) {
                Notice(stringResource(R.string.model_storage, "%.2f".format(model.requiredBytes / 1048576.0), "%.1f".format(device.storageBytes / 1073741824.0)))
                TextButton(onClick = vm::initializeModels) { Text(stringResource(R.string.initialize_engine)) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.recent_results), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { vm.navigate(Screen.PROJECTS) }) { Text(stringResource(R.string.view_all)) }
            }
        }
        if (projects.isEmpty()) item { EmptyState(R.string.empty_home, R.string.empty_home_body, Icons.Outlined.PhotoLibrary) }
        else items(projects.take(5), key = { it.id }) { project -> ProjectRow(vm, project) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable private fun Benefit(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, body: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column { Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable fun EmptyState(title: Int, body: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable fun ProjectRow(vm: AppViewModel, project: PhotoProject) {
    OutlinedCard(onClick = { vm.openProject(project.id) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PhotoThumbnail(project.outputPath ?: project.sourcePath, vm.graph.files, Modifier.size(70.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(statusLabel(project.state)), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.dimensions, if (project.outputWidth > 0) project.outputWidth else project.width,
                    if (project.outputHeight > 0) project.outputHeight else project.height), style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable fun LibraryScreen(vm: AppViewModel, projects: List<PhotoProject>, allProjects: Boolean) {
    if (projects.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(if (allProjects) R.string.empty_projects else R.string.empty_history,
                if (allProjects) R.string.empty_projects_body else R.string.empty_history_body, Icons.Outlined.Collections)
        }
    } else LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(projects, key = { it.id }) { project ->
            OutlinedCard(onClick = { vm.openProject(project.id) }, shape = RoundedCornerShape(14.dp)) {
                PhotoThumbnail(project.outputPath ?: project.sourcePath, vm.graph.files, Modifier.fillMaxWidth().aspectRatio(1f))
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(statusLabel(project.state)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(project.updatedAt)), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.dimensions, if (project.outputWidth > 0) project.outputWidth else project.width,
                        if (project.outputHeight > 0) project.outputHeight else project.height), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable fun ImportModeScreen(vm: AppViewModel, projects: List<PhotoProject>, start: (List<String>) -> Unit) {
    val ids by vm.importIds.collectAsStateWithLifecycle()
    val imported = projects.filter { it.id in ids }
    var primary by rememberSaveable(ids) { mutableStateOf(ids.firstOrNull()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(R.string.selected_count, imported.size), style = MaterialTheme.typography.headlineMedium) }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.batch_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.batch_description), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { start(ids) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.start_batch, imported.size)) }
                }
            }
        }
        item { SectionTitle(R.string.reference_title); Text(stringResource(R.string.reference_description), style = MaterialTheme.typography.bodyMedium) }
        items(imported, key = { it.id }) { project ->
            OutlinedCard(onClick = { primary = project.id }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = project.id == primary, onClick = { primary = project.id })
                    PhotoThumbnail(project.sourcePath, vm.graph.files, Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)))
                    Text(project.name, Modifier.padding(start = 12.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item {
            Notice(stringResource(R.string.reference_dependency))
            OutlinedButton(onClick = { primary?.let { vm.referenceProject(it, ids) } }, Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(stringResource(R.string.create_reference_project))
            }
        }
    }
}
