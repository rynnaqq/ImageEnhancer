package dev.localphoto.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

object RestorationPixels {
    fun rgbToLab(argb: Int): FloatArray {
        val red = srgbToLinear((argb ushr 16 and 0xff) / 255.0)
        val green = srgbToLinear((argb ushr 8 and 0xff) / 255.0)
        val blue = srgbToLinear((argb and 0xff) / 255.0)

        val x = (red * 0.4124564 + green * 0.3575761 + blue * 0.1804375) / D65_X
        val y = (red * 0.2126729 + green * 0.7151522 + blue * 0.0721750) / D65_Y
        val z = (red * 0.0193339 + green * 0.1191920 + blue * 0.9503041) / D65_Z
        val fx = labCurve(x)
        val fy = labCurve(y)
        val fz = labCurve(z)
        return floatArrayOf(
            (116.0 * fy - 16.0).toFloat(),
            (500.0 * (fx - fy)).toFloat(),
            (200.0 * (fy - fz)).toFloat(),
        )
    }

    fun labToArgb(l: Float, a: Float, b: Float, alpha: Int = 255): Int {
        val safeL = if (l.isFinite()) l.coerceIn(0f, 100f).toDouble() else 0.0
        val safeA = if (a.isFinite()) a.toDouble() else 0.0
        val safeB = if (b.isFinite()) b.toDouble() else 0.0
        val fy = (safeL + 16.0) / 116.0
        val fx = fy + safeA / 500.0
        val fz = fy - safeB / 200.0
        val x = D65_X * inverseLabCurve(fx)
        val y = D65_Y * inverseLabCurve(fy)
        val z = D65_Z * inverseLabCurve(fz)

        val red = linearToSrgb(x * 3.2404542 - y * 1.5371385 - z * 0.4985314)
        val green = linearToSrgb(-x * 0.9692660 + y * 1.8760108 + z * 0.0415560)
        val blue = linearToSrgb(x * 0.0556434 - y * 0.2040259 + z * 1.0572252)
        return (alpha.coerceIn(0, 255) shl 24) or
            (toChannel(red) shl 16) or
            (toChannel(green) shl 8) or
            toChannel(blue)
    }

    fun rasterMask(width: Int, height: Int, strokes: List<RepairStroke>): BooleanArray {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        val mask = BooleanArray(width * height)
        val normalized = RestorationSettings(maskStrokes = strokes).normalized().maskStrokes
        for (stroke in normalized) {
            val radius = stroke.radius * min(width, height)
            if (stroke.points.size == 1) {
                paintSegment(mask, width, height, stroke.points[0], stroke.points[0], radius, !stroke.erase)
            } else {
                for (index in 1 until stroke.points.size) {
                    paintSegment(
                        mask,
                        width,
                        height,
                        stroke.points[index - 1],
                        stroke.points[index],
                        radius,
                        !stroke.erase,
                    )
                }
            }
        }
        return mask
    }

    fun blendMasked(
        original: IntArray,
        generated: IntArray,
        mask: BooleanArray,
        strength: Float,
    ): IntArray {
        require(original.size == generated.size && original.size == mask.size) {
            "Pixels and mask must have equal lengths"
        }
        val amount = strength.finiteClamped(0f, 100f, 100f) / 100f
        return IntArray(original.size) { index ->
            val source = original[index]
            if (!mask[index] || amount == 0f) {
                source
            } else {
                val replacement = generated[index]
                val red = mix(source ushr 16 and 0xff, replacement ushr 16 and 0xff, amount)
                val green = mix(source ushr 8 and 0xff, replacement ushr 8 and 0xff, amount)
                val blue = mix(source and 0xff, replacement and 0xff, amount)
                (source ushr 24 shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
    }

    fun detectScratches(pixels: IntArray, width: Int, height: Int): BooleanArray {
        require(width > 0 && height > 0 && pixels.size == width * height) {
            "Pixel count must match positive image dimensions"
        }
        if (width < 5 || height < 5) return BooleanArray(pixels.size)
        val luminance = IntArray(pixels.size) { index ->
            val pixel = pixels[index]
            ((pixel ushr 16 and 0xff) * 54 + (pixel ushr 8 and 0xff) * 183 + (pixel and 0xff) * 19) ushr 8
        }
        val candidates = BooleanArray(pixels.size)
        for (y in 2 until height - 2) {
            for (x in 2 until width - 2) {
                val center = luminance[y * width + x]
                val left = luminance[y * width + x - 2]
                val right = luminance[y * width + x + 2]
                val top = luminance[(y - 2) * width + x]
                val bottom = luminance[(y + 2) * width + x]
                val horizontalContrast = abs(center - (left + right) / 2) >= SCRATCH_CONTRAST &&
                    abs(left - right) <= SCRATCH_SIDE_VARIATION
                val verticalContrast = abs(center - (top + bottom) / 2) >= SCRATCH_CONTRAST &&
                    abs(top - bottom) <= SCRATCH_SIDE_VARIATION
                candidates[y * width + x] = horizontalContrast || verticalContrast
            }
        }

        val result = BooleanArray(pixels.size)
        val visited = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        val component = IntArray(pixels.size)
        val minimumLength = max(6, min(width, height) / 5)
        val maximumCoverage = max(1, pixels.size / 10)
        for (start in candidates.indices) {
            if (!candidates[start] || visited[start]) continue
            var queueRead = 0
            var queueWrite = 1
            var componentSize = 0
            var minX = start % width
            var maxX = minX
            var minY = start / width
            var maxY = minY
            queue[0] = start
            visited[start] = true
            while (queueRead < queueWrite) {
                val index = queue[queueRead++]
                component[componentSize++] = index
                val x = index % width
                val y = index / width
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
                for (ny in max(0, y - 1)..min(height - 1, y + 1)) {
                    for (nx in max(0, x - 1)..min(width - 1, x + 1)) {
                        val neighbor = ny * width + nx
                        if (candidates[neighbor] && !visited[neighbor]) {
                            visited[neighbor] = true
                            queue[queueWrite++] = neighbor
                        }
                    }
                }
            }
            val componentWidth = maxX - minX + 1
            val componentHeight = maxY - minY + 1
            val longSide = max(componentWidth, componentHeight)
            val shortSide = min(componentWidth, componentHeight)
            if (
                componentSize <= maximumCoverage &&
                longSide >= minimumLength &&
                longSide >= shortSide * 3
            ) {
                for (index in 0 until componentSize) result[component[index]] = true
            }
        }
        return result
    }

    private fun paintSegment(
        mask: BooleanArray,
        width: Int,
        height: Int,
        start: RepairPoint,
        end: RepairPoint,
        radius: Float,
        value: Boolean,
    ) {
        val x1 = start.x * (width - 1)
        val y1 = start.y * (height - 1)
        val x2 = end.x * (width - 1)
        val y2 = end.y * (height - 1)
        val left = (min(x1, x2) - radius).toInt().coerceAtLeast(0)
        val top = (min(y1, y2) - radius).toInt().coerceAtLeast(0)
        val right = (max(x1, x2) + radius).toInt().coerceAtMost(width - 1)
        val bottom = (max(y1, y2) + radius).toInt().coerceAtMost(height - 1)
        val deltaX = x2 - x1
        val deltaY = y2 - y1
        val lengthSquared = deltaX * deltaX + deltaY * deltaY
        val radiusSquared = radius * radius
        for (y in top..bottom) {
            for (x in left..right) {
                val position = if (lengthSquared == 0f) {
                    0f
                } else {
                    (((x - x1) * deltaX + (y - y1) * deltaY) / lengthSquared).coerceIn(0f, 1f)
                }
                val nearestX = x1 + position * deltaX
                val nearestY = y1 + position * deltaY
                val distanceX = x - nearestX
                val distanceY = y - nearestY
                if (distanceX * distanceX + distanceY * distanceY <= radiusSquared) {
                    mask[y * width + x] = value
                }
            }
        }
    }

    private fun srgbToLinear(value: Double): Double =
        if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)

    private fun linearToSrgb(value: Double): Double =
        if (value <= 0.0031308) value * 12.92 else 1.055 * max(0.0, value).pow(1.0 / 2.4) - 0.055

    private fun labCurve(value: Double): Double =
        if (value > LAB_EPSILON) value.pow(1.0 / 3.0) else (LAB_KAPPA * value + 16.0) / 116.0

    private fun inverseLabCurve(value: Double): Double {
        val cubed = value * value * value
        return if (cubed > LAB_EPSILON) cubed else (116.0 * value - 16.0) / LAB_KAPPA
    }

    private fun toChannel(value: Double): Int = (value.coerceIn(0.0, 1.0) * 255.0).roundToInt()

    private fun mix(original: Int, generated: Int, amount: Float): Int =
        (original + (generated - original) * amount).roundToInt().coerceIn(0, 255)

    private const val D65_X = 0.95047
    private const val D65_Y = 1.0
    private const val D65_Z = 1.08883
    private const val LAB_EPSILON = 216.0 / 24389.0
    private const val LAB_KAPPA = 24389.0 / 27.0
    private const val SCRATCH_CONTRAST = 45
    private const val SCRATCH_SIDE_VARIATION = 24
}
