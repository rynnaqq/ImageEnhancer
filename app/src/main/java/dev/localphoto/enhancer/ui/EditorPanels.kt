package dev.localphoto.enhancer.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.localphoto.core.*
import dev.localphoto.enhancer.R
import dev.localphoto.enhancer.ai.DeviceCapabilities
import kotlin.math.roundToInt

@Composable fun SectionTitle(@StringRes title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
}

@Composable fun AdjustmentSlider(@StringRes label: Int, value: Float, range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit) {
    val description = stringResource(label)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Text(if (range.endInclusive <= 3f) "%.2f".format(value) else value.roundToInt().toString(),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = description })
    }
}

@Composable fun ToolCategory(@StringRes title: Int, initiallyExpanded: Boolean = false,
    reset: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (expanded) Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            content()
            if (reset != null) TextButton(onClick = reset, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.reset)) }
        }
    }
}

@Composable fun ResolutionControls(settings: EnhanceSettings, width: Int, height: Int,
    device: DeviceCapabilities, onChange: (EnhanceSettings) -> Unit) {
    val a = settings.adjustments
    SectionTitle(R.string.resolution)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 2, 4, 8).forEach { scale ->
            FilterChip(selected = a.scale == scale, onClick = { onChange(settings.copy(adjustments = a.copy(scale = scale))) },
                label = { Text(stringResource(R.string.multiplier, scale)) })
        }
    }
    Text(stringResource(R.string.output_dimensions, width.toLong() * a.scale, height.toLong() * a.scale),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val memory = MemoryPolicy.assess(width, height, a.scale, device.processingBudget)
    if (!memory.isSafe) Notice(stringResource(R.string.source_sampling_warning))
    else if (memory.safeScale < a.scale) Notice(stringResource(R.string.safe_scale, memory.safeScale))
    Text(stringResource(R.string.sr_description), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

@Composable fun ProfileControls(settings: EnhanceSettings, onChange: (EnhanceSettings) -> Unit) {
    SectionTitle(R.string.profile)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Profile.entries.forEach { profile ->
            FilterChip(selected = profile == settings.profile, onClick = { onChange(settings.copy(profile = profile)) },
                label = { Text(stringResource(profileLabel(profile))) })
        }
    }
    Text(stringResource(R.string.profile_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable fun OutputControls(settings: EnhanceSettings, onChange: (EnhanceSettings) -> Unit) {
    ToolCategory(R.string.export_settings) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutputFormat.entries.forEach { format ->
                FilterChip(settings.output.format == format, onClick = { onChange(settings.copy(output = settings.output.copy(format = format))) },
                    label = { Text(format.name) })
            }
        }
        if (settings.output.format != OutputFormat.PNG) AdjustmentSlider(R.string.export_quality,
            settings.output.quality.toFloat(), 1f..100f) { onChange(settings.copy(output = settings.output.copy(quality = it.roundToInt()))) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.strip_metadata), Modifier.weight(1f))
            Switch(settings.output.stripMetadata, onCheckedChange = { onChange(settings.copy(output = settings.output.copy(stripMetadata = it))) })
        }
        Text(stringResource(R.string.strip_metadata_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!settings.output.stripMetadata) Text(stringResource(R.string.metadata_note), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun TransformControls(settings: EnhanceSettings, onChange: (EnhanceSettings) -> Unit) {
    val t = settings.transform
    ToolCategory(R.string.transform, reset = { onChange(settings.copy(transform = TransformSettings())) }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { onChange(settings.copy(transform = t.copy(rotationDegrees = t.rotationDegrees - 90))) }) { Icon(Icons.AutoMirrored.Outlined.RotateLeft, stringResource(R.string.rotate_left)) }
            IconButton(onClick = { onChange(settings.copy(transform = t.copy(rotationDegrees = t.rotationDegrees + 90))) }) { Icon(Icons.AutoMirrored.Outlined.RotateRight, stringResource(R.string.rotate_right)) }
            IconButton(onClick = { onChange(settings.copy(transform = t.copy(flipHorizontal = !t.flipHorizontal))) }) { Icon(Icons.Outlined.Flip, stringResource(R.string.flip_horizontal)) }
            IconButton(onClick = { onChange(settings.copy(transform = t.copy(flipVertical = !t.flipVertical))) }) { Icon(Icons.Outlined.FlipCameraAndroid, stringResource(R.string.flip_vertical)) }
        }
        AdjustmentSlider(R.string.straighten, t.straightenDegrees, -15f..15f) { onChange(settings.copy(transform = t.copy(straightenDegrees = it))) }
        AdjustmentSlider(R.string.crop_left, t.cropLeft * 100f, 0f..((t.cropRight - 0.05f) * 100f).coerceAtLeast(0f)) {
            onChange(settings.copy(transform = t.copy(cropLeft = it / 100f)))
        }
        AdjustmentSlider(R.string.crop_top, t.cropTop * 100f, 0f..((t.cropBottom - 0.05f) * 100f).coerceAtLeast(0f)) {
            onChange(settings.copy(transform = t.copy(cropTop = it / 100f)))
        }
        AdjustmentSlider(R.string.crop_right, t.cropRight * 100f, ((t.cropLeft + 0.05f) * 100f).coerceAtMost(100f)..100f) {
            onChange(settings.copy(transform = t.copy(cropRight = it / 100f)))
        }
        AdjustmentSlider(R.string.crop_bottom, t.cropBottom * 100f, ((t.cropTop + 0.05f) * 100f).coerceAtMost(100f)..100f) {
            onChange(settings.copy(transform = t.copy(cropBottom = it / 100f)))
        }
    }
}

@Composable fun ManualControls(settings: EnhanceSettings, onChange: (EnhanceSettings) -> Unit) {
    val a = settings.adjustments
    fun update(value: Adjustments) = onChange(settings.copy(adjustments = value))
    ToolCategory(R.string.denoise, reset = { update(a.copy(denoise = 0f)) }) {
        AdjustmentSlider(R.string.strength, a.denoise, 0f..100f) { update(a.copy(denoise = it)) }
        AdjustmentSlider(R.string.detail_preservation, a.detailPreservation, 0f..100f) { update(a.copy(detailPreservation = it)) }
        Text(stringResource(R.string.conventional_filters), style = MaterialTheme.typography.bodySmall)
    }
    ToolCategory(R.string.deblur, reset = { update(a.copy(deblur = 0f)) }) {
        AdjustmentSlider(R.string.strength, a.deblur, 0f..100f) { update(a.copy(deblur = it)) }
        Text(stringResource(R.string.conventional_filters), style = MaterialTheme.typography.bodySmall)
    }
    ToolCategory(R.string.sharpen, reset = { update(a.copy(sharpen = 0f)) }) {
        AdjustmentSlider(R.string.strength, a.sharpen, 0f..100f) { update(a.copy(sharpen = it)) }
    }
    ToolCategory(R.string.lighting, reset = { update(a.copy(exposure = 0f, brightness = 0f, contrast = 0f, highlights = 0f,
        shadows = 0f, whitePoint = 0f, blackPoint = 0f, gamma = 1f)) }) {
        AdjustmentSlider(R.string.exposure, a.exposure, -3f..3f) { update(a.copy(exposure = it)) }
        AdjustmentSlider(R.string.brightness, a.brightness, -100f..100f) { update(a.copy(brightness = it)) }
        AdjustmentSlider(R.string.contrast, a.contrast, -100f..100f) { update(a.copy(contrast = it)) }
        AdjustmentSlider(R.string.highlights, a.highlights, -100f..100f) { update(a.copy(highlights = it)) }
        AdjustmentSlider(R.string.shadows, a.shadows, -100f..100f) { update(a.copy(shadows = it)) }
        AdjustmentSlider(R.string.white_point, a.whitePoint, -100f..100f) { update(a.copy(whitePoint = it)) }
        AdjustmentSlider(R.string.black_point, a.blackPoint, -100f..100f) { update(a.copy(blackPoint = it)) }
        AdjustmentSlider(R.string.gamma, a.gamma, 0.2f..3f) { update(a.copy(gamma = it)) }
    }
    ToolCategory(R.string.color, reset = { update(a.copy(temperature = 0f, tint = 0f, saturation = 0f, vibrance = 0f,
        hue = 0f, redBalance = 0f, greenBalance = 0f, blueBalance = 0f, fadedColor = 0f)) }) {
        AdjustmentSlider(R.string.temperature, a.temperature, -100f..100f) { update(a.copy(temperature = it)) }
        AdjustmentSlider(R.string.tint, a.tint, -100f..100f) { update(a.copy(tint = it)) }
        AdjustmentSlider(R.string.saturation, a.saturation, -100f..100f) { update(a.copy(saturation = it)) }
        AdjustmentSlider(R.string.vibrance, a.vibrance, -100f..100f) { update(a.copy(vibrance = it)) }
        AdjustmentSlider(R.string.hue, a.hue, -180f..180f) { update(a.copy(hue = it)) }
        AdjustmentSlider(R.string.red_balance, a.redBalance, -100f..100f) { update(a.copy(redBalance = it)) }
        AdjustmentSlider(R.string.green_balance, a.greenBalance, -100f..100f) { update(a.copy(greenBalance = it)) }
        AdjustmentSlider(R.string.blue_balance, a.blueBalance, -100f..100f) { update(a.copy(blueBalance = it)) }
        AdjustmentSlider(R.string.faded_color, a.fadedColor, 0f..100f) { update(a.copy(fadedColor = it)) }
    }
}

@Composable fun RestorationDependencies() {
    var explanation by remember { mutableStateOf<Int?>(null) }
    SectionTitle(R.string.restoration_tools)
    Text(stringResource(R.string.dependency_explanation), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    listOf(R.string.old_photo to R.string.old_photo_dependency, R.string.face to R.string.face_dependency,
        R.string.colorization to R.string.colorization_dependency, R.string.reconstruction to R.string.reconstruction_dependency).forEach { (title, body) ->
        OutlinedCard(modifier = Modifier.fillMaxWidth(), onClick = { explanation = body }) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(title), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.model_dependency), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.Info, stringResource(R.string.not_available))
            }
        }
    }
    explanation?.let { body -> AlertDialog(onDismissRequest = { explanation = null },
        title = { Text(stringResource(R.string.not_available)) }, text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onClick = { explanation = null }) { Text(stringResource(R.string.close)) } }) }
}

@Composable fun Notice(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}
