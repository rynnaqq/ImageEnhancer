package dev.localphoto.enhancer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localphoto.core.*
import dev.localphoto.enhancer.*
import dev.localphoto.enhancer.R
import dev.localphoto.enhancer.data.PhotoProject
import org.json.JSONArray
import org.json.JSONObject
import dev.localphoto.enhancer.data.SettingsCodec
import android.graphics.Matrix
import android.graphics.RectF
import kotlin.math.roundToInt

@Composable fun EditorScreen(vm: AppViewModel, project: PhotoProject, settings: EnhanceSettings, enhance: () -> Unit) {
    val metrics by vm.metrics.collectAsStateWithLifecycle()
    val device by vm.capabilities.collectAsStateWithLifecycle()
    var delete by remember { mutableStateOf(false) }
    var maskClearedForTransform by remember(project.id) { mutableStateOf(false) }
    val update: (EnhanceSettings) -> Unit = { next ->
        val transformChanged = next.transform.normalized() != settings.transform.normalized()
        if (transformChanged && settings.restoration.maskStrokes.isNotEmpty()) {
            vm.updateDraft(next.copy(restoration = next.restoration.copy(maskStrokes = emptyList())))
            maskClearedForTransform = true
        } else {
            vm.updateDraft(next)
            if (next.restoration.maskStrokes.isNotEmpty()) maskClearedForTransform = false
        }
    }
    val completedTransform = if (project.outputPath != null) runCatching {
        SettingsCodec.decode(JSONObject(project.planJson).getJSONObject("settings").toString()).transform
    }.getOrDefault(settings.transform) else settings.transform
    val transformedDimensions = remember(settings.transform, project.inputWidth, project.inputHeight) {
        val t = settings.transform.normalized()
        val rectangle = RectF(0f, 0f, (project.inputWidth * (t.cropRight - t.cropLeft)).toInt().coerceAtLeast(1).toFloat(),
            (project.inputHeight * (t.cropBottom - t.cropTop)).toInt().coerceAtLeast(1).toFloat())
        Matrix().apply { postRotate(t.rotationDegrees + t.straightenDegrees) }.mapRect(rectangle)
        rectangle.width().roundToInt().coerceAtLeast(1) to rectangle.height().roundToInt().coerceAtLeast(1)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ComparisonViewer(project.basePath ?: project.sourcePath, project.outputPath, vm.graph.files, completedTransform)
            if (project.outputWidth > 0 && project.outputPath != null) Text(stringResource(R.string.dimensions, project.outputWidth, project.outputHeight),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        project.noticeCode?.let { notice -> item { Notice(stringResource(messageLabel(notice))) } }
        project.errorCode?.let { error -> item { Notice(stringResource(messageLabel(error))) } }
        if (project.outputPath != null) item {
            if (project.galleryUri != null) Text(stringResource(R.string.saved_automatically), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.saveCopy(project) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.save_copy)) }
                OutlinedButton(onClick = { vm.share(project) }) { Icon(Icons.Outlined.Share, stringResource(R.string.share)) }
            }
            TextButton(onClick = { vm.editAgain(project.id) }) { Icon(Icons.Outlined.Edit, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.edit_again)) }
        }
        val referenceCount = runCatching { JSONArray(project.referencePathsJson).length() }.getOrDefault(0)
        if (referenceCount > 0) item { Notice(stringResource(R.string.reference_count, referenceCount)); Text(stringResource(R.string.reference_dependency), style = MaterialTheme.typography.bodySmall) }
        if (project.editable) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(settings.auto, onClick = { update(settings.copy(auto = true)) }, label = { Text(stringResource(R.string.auto)) },
                        leadingIcon = { Icon(Icons.Outlined.AutoFixHigh, null) })
                    FilterChip(!settings.auto, onClick = { update(settings.copy(auto = false)) }, label = { Text(stringResource(R.string.manual)) },
                        leadingIcon = { Icon(Icons.Outlined.Tune, null) })
                }
            }
            if (settings.auto) item {
                Text(stringResource(R.string.auto_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.auto_description), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                metrics?.let { image ->
                    val plan = PipelinePlanner.plan(image, settings)
                    SectionTitle(R.string.detected)
                    if (plan.detected.isEmpty()) Text(stringResource(R.string.no_defects), style = MaterialTheme.typography.bodyMedium)
                    plan.detected.forEach { defect -> Text("• ${stringResource(defectLabel(defect))}", style = MaterialTheme.typography.bodyMedium) }
                    SectionTitle(R.string.planned)
                    plan.stages.filter { it !in setOf(Stage.DECODE, Stage.ANALYZE, Stage.EXPORT) }.forEach { stage ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(stageLabel(stage)), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            item { TransformControls(settings, update) }
            if (maskClearedForTransform) item { Notice(stringResource(R.string.repair_mask_transform_cleared)) }
            item { ResolutionControls(settings, transformedDimensions.first, transformedDimensions.second, device, update) }
            if (!settings.auto) item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { ManualControls(settings, update) } }
            item { ProfileControls(settings, update) }
            item { OutputControls(settings, update) }
            item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RestorationControls(settings, update, project.basePath ?: project.sourcePath, vm.graph.files)
            } }
            item {
                Button(onClick = enhance, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Outlined.AutoFixHigh, null); Spacer(Modifier.width(8.dp)); Text(stringResource(if (project.outputPath != null) R.string.enhance_again else R.string.enhance))
                }
                TextButton(onClick = { vm.saveDraft(true) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_project)) }
            }
            item {
                if (project.galleryUri != null) TextButton(onClick = { vm.deleteGalleryCopy(project) }) { Text(stringResource(R.string.delete_saved_copy)) }
                TextButton(onClick = { delete = true }) { Text(stringResource(R.string.delete_project), color = MaterialTheme.colorScheme.error) }
            }
        } else item { Button(onClick = { vm.navigate(Screen.QUEUE) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.queue)) } }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text(stringResource(R.string.delete_project)) },
        text = { Text(stringResource(R.string.delete_project_body)) }, confirmButton = { TextButton(onClick = { delete = false; vm.deleteProject(project.id) }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text(stringResource(R.string.keep)) } })
}

@Composable fun QueueScreen(vm: AppViewModel, projects: List<PhotoProject>, resume: () -> Unit) {
    val globalActive = projects.firstOrNull { it.state == ProjectStatus.PROCESSING }
    val latestBatch = globalActive?.batchId ?: projects.filter { it.state != ProjectStatus.DRAFT }.maxByOrNull { it.updatedAt }?.batchId
    val queue = projects.filter { it.state != ProjectStatus.DRAFT && (latestBatch == null || it.batchId == latestBatch) }.sortedBy { it.createdAt }
    if (queue.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyState(R.string.empty_queue, R.string.empty_queue_body, Icons.Outlined.HourglassEmpty) }
        return
    }
    val active = queue.firstOrNull { it.state == ProjectStatus.PROCESSING }
    val waiting = queue.count { it.state == ProjectStatus.QUEUED }
    val paused = queue.count { it.state == ProjectStatus.INTERRUPTED }
    val completed = queue.count { it.state == ProjectStatus.COMPLETED }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(stringResource(R.string.processing_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.queue_summary, completed, waiting, paused), modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
            val terminal = queue.count { it.state in setOf(ProjectStatus.COMPLETED, ProjectStatus.FAILED, ProjectStatus.CANCELLED) }
            LinearProgressIndicator(progress = { (terminal + (active?.progress ?: 0f)) / queue.size }, Modifier.fillMaxWidth().padding(top = 12.dp))
        }
        if (active != null) item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PhotoThumbnail(active.sourcePath, vm.graph.files, Modifier.fillMaxWidth().height(180.dp))
                    Text(active.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.batch_position, queue.indexOfFirst { it.id == active.id } + 1, queue.size), style = MaterialTheme.typography.bodySmall)
                    runCatching { Stage.valueOf(active.stage) }.getOrNull()?.let { Text(stringResource(stageLabel(it)), color = MaterialTheme.colorScheme.primary) }
                    LinearProgressIndicator(progress = { active.progress }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.progress_percent, (active.progress * 100).toInt()), style = MaterialTheme.typography.labelLarge)
                    val planned = runCatching { JSONObject(active.planJson).getJSONArray("stages") }.getOrNull()
                    if (planned != null) {
                        val stages = (0 until planned.length()).mapNotNull { runCatching { Stage.valueOf(planned.getString(it)) }.getOrNull() }
                        val currentIndex = stages.indexOfFirst { it.name == active.stage }
                        stages.forEachIndexed { index, stage ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (index < currentIndex || active.stage == Stage.EXPORT.name) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                    null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(stringResource(stageLabel(stage)), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Text(stringResource(R.string.processing_slow), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { vm.cancel(false) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel_current)) }
                }
            }
        }
        if (active != null || waiting > 0) item { TextButton(onClick = { vm.cancel(true) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel_queue)) } }
        if (active == null && (waiting > 0 || paused > 0)) item { Button(onClick = resume, Modifier.fillMaxWidth()) { Text(stringResource(R.string.resume_queue)) } }
        items(queue, key = { it.id }) { project ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ProjectRow(vm, project)
                if (project.state in setOf(ProjectStatus.FAILED, ProjectStatus.CANCELLED)) {
                    project.errorCode?.let { Text(stringResource(messageLabel(it)), style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { vm.startBatch(listOf(project.id)) }) { Text(stringResource(R.string.retry)) }
                }
            }
        }
    }
}
