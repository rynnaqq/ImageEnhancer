package dev.localphoto.enhancer.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localphoto.enhancer.*
import dev.localphoto.enhancer.R
import org.json.JSONObject

@Composable fun SettingsScreen(vm: AppViewModel) {
    val settings by vm.defaults.collectAsStateWithLifecycle()
    val models by vm.modelCatalog.collectAsStateWithLifecycle()
    val device by vm.capabilities.collectAsStateWithLifecycle()
    var licenses by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val orderedModels = MODEL_ORDER.mapNotNull(models::get) + models.values.filter { it.id !in MODEL_ORDER }.sortedBy { it.label }
    val modelBytes = models.values.sumOf { it.bytes }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ProfileControls(settings, vm::updateDefaults) }
        item { OutputControls(settings, vm::updateDefaults) }
        item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RestorationControls(settings, vm::updateDefaults, null, vm.graph.files)
        } }
        item {
            SectionTitle(R.string.local_engine)
            Text(stringResource(R.string.model_storage, "%.2f".format(modelBytes / 1048576.0), "%.1f".format(device.storageBytes / 1073741824.0)), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.device_info, device.androidVersion, device.cores, (device.processingBudget / 1048576).toString()), style = MaterialTheme.typography.bodySmall)
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                orderedModels.forEach { model ->
                    val stateText = when {
                        model.ready -> stringResource(R.string.model_ready)
                        model.preparing -> stringResource(R.string.model_preparing)
                        model.error != null -> stringResource(
                            if (model.error == "model_integrity") R.string.model_integrity_failed else messageLabel(model.error),
                        )
                        else -> stringResource(R.string.model_included)
                    }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(model.label, style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.model_status_size, stateText, "%.1f".format(model.bytes / 1048576.0)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (model.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (model.ready) Icon(Icons.Outlined.CheckCircle, stringResource(R.string.model_ready),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (models.isNotEmpty() && models.values.all { it.ready }) Text(stringResource(R.string.engine_ready),
                Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
            else OutlinedButton(onClick = vm::initializeModels, enabled = models.values.none { it.preparing }, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(if (models.values.any { it.error != null }) R.string.retry else R.string.initialize_engine))
            }
            Notice(stringResource(R.string.baseline_notice))
        }
        item {
            SectionTitle(R.string.privacy_title)
            Text(stringResource(R.string.privacy_body), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { licenses = true }) { Text(stringResource(R.string.licenses)) }
        }
        item { SectionTitle(R.string.tiff_title); Notice(stringResource(R.string.tiff_dependency)) }
    }
    if (licenses) {
        val bundledLicenses = remember { readBundledModelLicenses(context) }
        AlertDialog(onDismissRequest = { licenses = false }, title = { Text(stringResource(R.string.licenses)) },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.license_body))
                bundledLicenses.forEach { (name, body) ->
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(body, style = MaterialTheme.typography.bodySmall)
                }
            } }, confirmButton = { TextButton(onClick = { licenses = false }) { Text(stringResource(R.string.close)) } })
    }
}

private fun readBundledModelLicenses(context: Context): List<Pair<String, String>> {
    val fromCatalog = runCatching {
        val manifest = JSONObject(context.assets.open("models/manifest.json").bufferedReader().use { it.readText() })
        val models = manifest.getJSONArray("models")
        buildList {
            for (index in 0 until models.length()) {
                models.getJSONObject(index).optString("licenseAsset").takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }.getOrDefault(emptyList())
    val archived = context.assets.list("licenses").orEmpty().map { "licenses/$it" }
    return (fromCatalog + archived).distinct().mapNotNull { asset ->
        runCatching { asset.substringAfterLast('/') to context.assets.open(asset).bufferedReader().use { it.readText() } }.getOrNull()
    }
}

private val MODEL_ORDER = listOf("espcn-x3", "yunet", "face-swinir", "color-ddcolor", "repair-lama")
