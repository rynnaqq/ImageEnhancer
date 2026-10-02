package dev.localphoto.enhancer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.localphoto.core.RepairPoint
import dev.localphoto.core.RepairStroke
import dev.localphoto.core.RestorationPixels
import dev.localphoto.core.TransformSettings
import dev.localphoto.enhancer.R
import dev.localphoto.enhancer.data.ImageFiles
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

fun fitImageBounds(
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
): Rect {
    if (containerWidth <= 0f || containerHeight <= 0f || imageWidth <= 0 || imageHeight <= 0) return Rect.Zero
    val scale = min(containerWidth / imageWidth, containerHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    val left = (containerWidth - width) / 2f
    val top = (containerHeight - height) / 2f
    return Rect(left, top, left + width, top + height)
}

fun normalizedRepairPoint(point: Offset, bounds: Rect): RepairPoint? {
    if (bounds.width <= 0f || bounds.height <= 0f || !bounds.contains(point)) return null
    return RepairPoint(
        x = ((point.x - bounds.left) / bounds.width).coerceIn(0f, 1f),
        y = ((point.y - bounds.top) / bounds.height).coerceIn(0f, 1f),
    )
}

fun repairMaskHasSelection(
    strokes: List<RepairStroke>,
    imageWidth: Int? = null,
    imageHeight: Int? = null,
): Boolean {
    if (strokes.isEmpty()) return false
    val dimensions = maskPresenceDimensions(imageWidth, imageHeight)
    return RestorationPixels.rasterMask(dimensions.width, dimensions.height, strokes).any { it }
}

data class RepairGesturePath(
    val points: List<RepairPoint>,
    val ended: Boolean = false,
)

fun advanceRepairGesture(path: RepairGesturePath, position: Offset, bounds: Rect): RepairGesturePath {
    if (path.ended) return path
    val point = normalizedRepairPoint(position, bounds) ?: return path.copy(ended = true)
    if (path.points.size >= MAX_POINTS_PER_GESTURE || path.points.lastOrNull() == point) return path
    return path.copy(points = path.points + point)
}

@Composable
fun RepairMaskEditor(
    sourcePath: String?,
    files: ImageFiles,
    transform: TransformSettings,
    strokes: List<RepairStroke>,
    onAddStroke: (RepairStroke) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberPhotoBitmap(sourcePath, files, transform, maxPixels = 2_000_000)
    val hasSelection = remember(strokes, bitmap?.width, bitmap?.height) {
        repairMaskHasSelection(strokes, bitmap?.width, bitmap?.height)
    }
    var brushRadius by rememberSaveable(sourcePath) { mutableFloatStateOf(0.025f) }
    var erase by rememberSaveable(sourcePath) { mutableStateOf(false) }
    val drawingErase = erase && hasSelection
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var currentPoints by remember { mutableStateOf<List<RepairPoint>>(emptyList()) }
    val currentAddStroke by rememberUpdatedState(onAddStroke)
    val brushDescription = stringResource(R.string.repair_brush_size)
    val canvasDescription = stringResource(R.string.repair_mask_canvas)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !drawingErase,
                onClick = { erase = false },
                label = { Text(stringResource(R.string.brush)) },
                leadingIcon = { Icon(Icons.Outlined.Brush, null) },
            )
            FilterChip(
                selected = drawingErase,
                onClick = { erase = true },
                enabled = hasSelection,
                label = { Text(stringResource(R.string.erase)) },
                leadingIcon = { Icon(Icons.Outlined.RemoveCircleOutline, null) },
            )
            TextButton(onClick = onClear, enabled = strokes.isNotEmpty()) {
                Icon(Icons.Outlined.Clear, null)
                Text(stringResource(R.string.clear_mask), Modifier.padding(start = 4.dp))
            }
        }
        Text(stringResource(R.string.repair_brush_size_value, (brushRadius * 100f).roundToInt()), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = brushRadius,
            onValueChange = { brushRadius = it },
            valueRange = 0.01f..0.10f,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = brushDescription },
        )
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap == null) {
                Text(
                    stringResource(if (sourcePath == null) R.string.repair_select_photo else R.string.preparing),
                    Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Canvas(
                    Modifier.fillMaxSize().onSizeChanged { previewSize = it }
                        .semantics { contentDescription = canvasDescription }
                        .pointerInput(bitmap, previewSize, drawingErase, brushRadius) {
                            awaitEachGesture {
                                val bounds = fitImageBounds(
                                    previewSize.width.toFloat(),
                                    previewSize.height.toFloat(),
                                    bitmap.width,
                                    bitmap.height,
                                )
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val first = normalizedRepairPoint(down.position, bounds) ?: return@awaitEachGesture
                                val pointer: PointerId = down.id
                                var path = RepairGesturePath(listOf(first))
                                currentPoints = path.points
                                down.consume()
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == pointer }
                                    if (change == null || !change.pressed) {
                                        if (path.points.isNotEmpty()) currentAddStroke(RepairStroke(path.points, brushRadius, drawingErase))
                                        currentPoints = emptyList()
                                        break
                                    }
                                    path = advanceRepairGesture(path, change.position, bounds)
                                    change.consume()
                                    if (path.ended) {
                                        if (path.points.isNotEmpty()) currentAddStroke(RepairStroke(path.points, brushRadius, drawingErase))
                                        currentPoints = emptyList()
                                        break
                                    }
                                    currentPoints = path.points
                                }
                            }
                        },
                ) {
                    val bounds = fitImageBounds(size.width, size.height, bitmap.width, bitmap.height)
                    drawImage(
                        image = bitmap.asImageBitmap(),
                        dstOffset = IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()),
                        dstSize = IntSize(max(1, bounds.width.roundToInt()), max(1, bounds.height.roundToInt())),
                    )
                    clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
                        drawContext.canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint())
                        strokes.forEach { drawRepairStroke(it, bounds) }
                        if (currentPoints.isNotEmpty()) drawRepairStroke(RepairStroke(currentPoints, brushRadius, drawingErase), bounds)
                        drawContext.canvas.restore()
                    }
                }
            }
        }
        Text(stringResource(R.string.repair_mask_help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!hasSelection) Notice(stringResource(R.string.repair_mask_required))
        else Text(stringResource(R.string.repair_mask_ready), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
    }
}

private fun maskPresenceDimensions(imageWidth: Int?, imageHeight: Int?): IntSize {
    if (imageWidth == null || imageHeight == null || imageWidth <= 0 || imageHeight <= 0) {
        return IntSize(MASK_PRESENCE_SHORT_SIDE, MASK_PRESENCE_SHORT_SIDE)
    }
    val scale = MASK_PRESENCE_SHORT_SIDE.toDouble() / min(imageWidth, imageHeight)
    var width = max(1, (imageWidth * scale).roundToInt())
    var height = max(1, (imageHeight * scale).roundToInt())
    val pixels = width.toLong() * height
    if (pixels > MAX_MASK_PRESENCE_PIXELS) {
        val reduction = sqrt(MAX_MASK_PRESENCE_PIXELS.toDouble() / pixels)
        width = max(1, (width * reduction).roundToInt())
        height = max(1, (height * reduction).roundToInt())
    }
    return IntSize(width, height)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRepairStroke(stroke: RepairStroke, bounds: Rect) {
    if (stroke.points.isEmpty()) return
    val radius = stroke.radius.coerceIn(0.001f, 0.25f) * min(bounds.width, bounds.height)
    val color = if (stroke.erase) Color.Transparent else Color(0xAA66E09B)
    val blendMode = if (stroke.erase) BlendMode.Clear else BlendMode.SrcOver
    val offsets = stroke.points.map { point ->
        Offset(bounds.left + point.x.coerceIn(0f, 1f) * bounds.width, bounds.top + point.y.coerceIn(0f, 1f) * bounds.height)
    }
    offsets.forEach { drawCircle(color, radius, it, blendMode = blendMode) }
    offsets.zipWithNext().forEach { (from, to) ->
        drawLine(color, from, to, strokeWidth = radius * 2f, blendMode = blendMode)
    }
}

private const val MAX_POINTS_PER_GESTURE = 1024
private const val MASK_PRESENCE_SHORT_SIDE = 512
private const val MAX_MASK_PRESENCE_PIXELS = 2_097_152L
