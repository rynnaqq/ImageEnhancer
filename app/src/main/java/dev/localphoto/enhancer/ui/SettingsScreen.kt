package dev.localphoto.enhancer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localphoto.enhancer.*
import dev.localphoto.enhancer.R

@Composable fun SettingsScreen(vm: AppViewModel) {
    val settings by vm.defaults.collectAsStateWithLifecycle()
    val model by vm.modelState.collectAsStateWithLifecycle()
    val device by vm.capabilities.collectAsStateWithLifecycle()
    var licenses by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ProfileControls(settings, vm::updateDefaults) }
        item { OutputControls(settings, vm::updateDefaults) }
        item {
            SectionTitle(R.string.local_engine)
            Text(stringResource(R.string.model_storage, "%.2f".format(model.requiredBytes / 1048576.0), "%.1f".format(device.storageBytes / 1073741824.0)), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.device_info, device.androidVersion, device.cores, (device.processingBudget / 1048576).toString()), style = MaterialTheme.typography.bodySmall)
            if (model.ready) Text(stringResource(R.string.engine_ready), color = MaterialTheme.colorScheme.primary)
            else OutlinedButton(onClick = vm::initializeModels) { Text(stringResource(R.string.initialize_engine)) }
            Notice(stringResource(R.string.baseline_notice))
        }
        item {
            SectionTitle(R.string.privacy_title)
            Text(stringResource(R.string.privacy_body), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { licenses = true }) { Text(stringResource(R.string.licenses)) }
        }
        item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { RestorationDependencies() } }
        item { SectionTitle(R.string.tiff_title); Notice(stringResource(R.string.tiff_dependency)) }
    }
    if (licenses) {
        val fullLicense = remember { context.assets.open("licenses/ONNX-MODEL-ZOO-APACHE-2.0.txt").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { licenses = false }, title = { Text(stringResource(R.string.licenses)) },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.license_body)); Text(fullLicense, style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton(onClick = { licenses = false }) { Text(stringResource(R.string.close)) } })
    }
}
