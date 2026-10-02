package dev.localphoto.enhancer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import dev.localphoto.core.TransformSettings
import dev.localphoto.enhancer.R
import dev.localphoto.enhancer.data.ImageFiles
import kotlin.math.*

@Composable fun ComparisonViewer(beforePath: String, afterPath: String?, files: ImageFiles,
    transform: TransformSettings, modifier: Modifier = Modifier) {
    val before = rememberPhotoBitmap(beforePath, files, transform, 4_000_000)
    val after = rememberPhotoBitmap(afterPath, files, maxPixels = 4_000_000)
    var divider by rememberSaveable(beforePath, afterPath) { mutableFloatStateOf(0.5f) }
    var zoom by rememberSaveable(beforePath) { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val description = stringResource(R.string.comparison_position)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 440.dp).aspectRatio(1.15f)
            .clip(RoundedCornerShape(18.dp)).background(Color(0xFF202520)).onSizeChanged { size = it }) {
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }
                .pointerInput(beforePath, afterPath) {
                    detectTransformGestures { _, movement, scale, _ ->
                        zoom = (zoom * scale).coerceIn(1f, 12f)
                        pan = if (zoom <= 1f) Offset.Zero else (pan + movement).let {
                            Offset(it.x.coerceIn(-size.width * zoom / 2, size.width * zoom / 2),
                                it.y.coerceIn(-size.height * zoom / 2, size.height * zoom / 2))
                        }
                    }
                }) {
                val input = before ?: return@Canvas
                val target = after ?: input
                val fit = min(this.size.width / target.width, this.size.height / target.height)
                val w = target.width * fit * zoom
                val h = target.height * fit * zoom
                val offset = IntOffset(((this.size.width - w) / 2 + pan.x).roundToInt(), ((this.size.height - h) / 2 + pan.y).roundToInt())
                val targetSize = IntSize(max(1, w.roundToInt()), max(1, h.roundToInt()))
                clipRect {
                    drawImage(input.asImageBitmap(), dstOffset = offset, dstSize = targetSize)
                    if (after != null && mode != 1) {
                        clipRect(left = if (mode == 2) 0f else this.size.width * divider) {
                            drawImage(target.asImageBitmap(), dstOffset = offset, dstSize = targetSize)
                        }
                    }
                }
                if (after != null && mode == 0) {
                    val x = this.size.width * divider
                    drawLine(Color.White, Offset(x, 0f), Offset(x, this.size.height), strokeWidth = 2.dp.toPx())
                    drawCircle(Color.White, radius = 14.dp.toPx(), center = Offset(x, this.size.height / 2))
                    drawLine(Color(0xFF43634D), Offset(x - 5.dp.toPx(), this.size.height / 2),
                        Offset(x + 5.dp.toPx(), this.size.height / 2), strokeWidth = 2.dp.toPx())
                }
            }
            if (after != null && mode == 0) {
                Box(Modifier.offset { IntOffset((size.width * divider).roundToInt() - 24.dp.roundToPx(), 0) }
                    .width(48.dp).fillMaxHeight().pointerInput(size) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            divider = (divider + drag.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        }
                    })
            }
            Surface(Modifier.align(Alignment.TopStart).padding(12.dp), color = Color.Black.copy(alpha = 0.45f),
                shape = RoundedCornerShape(6.dp)) {
                Text(stringResource(if (mode == 2) R.string.after else R.string.before), Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            if (after != null && mode == 0) Surface(Modifier.align(Alignment.TopEnd).padding(12.dp),
                color = Color.Black.copy(alpha = 0.45f), shape = RoundedCornerShape(6.dp)) {
                Text(stringResource(R.string.after), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = Color.White,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        if (after != null) {
            Slider(value = divider, onValueChange = { divider = it; mode = 0 },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = description })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { mode = 1 }) { Text(stringResource(R.string.before)) }
                TextButton(onClick = { mode = 0 }) { Text(stringResource(R.string.compare)) }
                TextButton(onClick = { mode = 2 }) { Text(stringResource(R.string.after)) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text(stringResource(R.string.fit)) }
            TextButton(onClick = {
                val image = after ?: before
                if (image != null && size.width > 0 && size.height > 0) {
                    zoom = (1f / min(size.width.toFloat() / image.width, size.height.toFloat() / image.height)).coerceIn(1f, 12f)
                    pan = Offset.Zero
                }
            }) { Text(stringResource(R.string.actual_pixels)) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(1f); if (zoom == 1f) pan = Offset.Zero }) {
                Icon(Icons.Outlined.ZoomOut, stringResource(R.string.zoom_out))
            }
            IconButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(12f) }) { Icon(Icons.Outlined.ZoomIn, stringResource(R.string.zoom_in)) }
        }
    }
}
