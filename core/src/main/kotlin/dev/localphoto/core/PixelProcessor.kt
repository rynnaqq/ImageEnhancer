package dev.localphoto.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Conventional, tile-sized pixel filters. Learned restoration and super-resolution
 * are intentionally outside this class and are supplied by the Android inference layer.
 */
object PixelProcessor {
    fun process(
        pixels: IntArray,
        width: Int,
        height: Int,
        adjustments: Adjustments,
    ): IntArray {
        validateImage(pixels, width, height)
        val normalized = adjustments.normalized()
        var working = pixels.copyOf()

        if (normalized.denoise > 0f) {
            working = denoise(working, width, height, normalized)
        }
        if (hasToneOrColorAdjustment(normalized)) {
            applyToneAndColor(working, normalized)
        }

        val edgeAmount = (
            normalized.deblur / 100.0 * 0.30 +
                normalized.sharpen / 100.0 * 0.35
            ).coerceAtMost(0.65)
        if (edgeAmount > 0.0) {
            working = refineEdges(working, width, height, edgeAmount, normalized.detailPreservation / 100.0)
        }
        return working
    }

    private fun denoise(
        source: IntArray,
        width: Int,
        height: Int,
        adjustments: Adjustments,
    ): IntArray {
        if (width == 1 && height == 1) return source
        val output = IntArray(source.size)
        val strength = adjustments.denoise / 100.0
        val preservation = adjustments.detailPreservation / 100.0
        val differenceThreshold = 30.0 + 90.0 * strength * (1.0 - 0.35 * preservation)
        val blend = strength * (0.90 - 0.25 * preservation)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val center = source[index]
                val centerAlpha = alpha(center)
                val centerRed = red(center)
                val centerGreen = green(center)
                val centerBlue = blue(center)
                var redSum = centerRed.toDouble()
                var greenSum = centerGreen.toDouble()
                var blueSum = centerBlue.toDouble()
                var weightSum = 1.0

                for (offsetY in -1..1) {
                    val neighborY = (y + offsetY).coerceIn(0, height - 1)
                    for (offsetX in -1..1) {
                        if (offsetX == 0 && offsetY == 0) continue
                        val neighborX = (x + offsetX).coerceIn(0, width - 1)
                        val neighbor = source[neighborY * width + neighborX]
                        if (abs(alpha(neighbor) - centerAlpha) > 8) continue
                        val colorDifference = max(
                            abs(red(neighbor) - centerRed),
                            max(
                                abs(green(neighbor) - centerGreen),
                                abs(blue(neighbor) - centerBlue),
                            ),
                        )
                        if (colorDifference > differenceThreshold) continue
                        val spatialWeight = if (offsetX != 0 && offsetY != 0) 0.7 else 1.0
                        redSum += red(neighbor) * spatialWeight
                        greenSum += green(neighbor) * spatialWeight
                        blueSum += blue(neighbor) * spatialWeight
                        weightSum += spatialWeight
                    }
                }

                output[index] = argb(
                    centerAlpha,
                    mix(centerRed.toDouble(), redSum / weightSum, blend),
                    mix(centerGreen.toDouble(), greenSum / weightSum, blend),
                    mix(centerBlue.toDouble(), blueSum / weightSum, blend),
                )
            }
        }
        return output
    }

    private fun applyToneAndColor(pixels: IntArray, adjustments: Adjustments) {
        val exposureMultiplier = 2.0.pow(adjustments.exposure.toDouble())
        val brightness = adjustments.brightness / 100.0 * 0.25
        val faded = adjustments.fadedColor / 100.0
        val contrast = 1.0 + adjustments.contrast / 100.0 + faded * 0.18
        val highlights = adjustments.highlights / 100.0 * 0.28
        val shadows = adjustments.shadows / 100.0 * 0.30
        val whitePoint = adjustments.whitePoint / 100.0 * 0.18
        val blackPoint = adjustments.blackPoint / 100.0 * 0.18
        val inverseGamma = 1.0 / adjustments.gamma
        val temperature = adjustments.temperature / 100.0 * 0.12
        val tint = adjustments.tint / 100.0 * 0.10
        val redBalance = adjustments.redBalance / 100.0 * 0.16
        val greenBalance = adjustments.greenBalance / 100.0 * 0.16
        val blueBalance = adjustments.blueBalance / 100.0 * 0.16
        val saturation = adjustments.saturation / 100.0
        val vibrance = adjustments.vibrance / 100.0
        val hueRotation = adjustments.hue / 360.0

        for (index in pixels.indices) {
            val pixel = pixels[index]
            var red = red(pixel) / 255.0
            var green = green(pixel) / 255.0
            var blue = blue(pixel) / 255.0

            red *= exposureMultiplier
            green *= exposureMultiplier
            blue *= exposureMultiplier
            red += brightness
            green += brightness
            blue += brightness

            red = (red - 0.5) * contrast + 0.5
            green = (green - 0.5) * contrast + 0.5
            blue = (blue - 0.5) * contrast + 0.5

            var luminance = luminance(red, green, blue).coerceIn(0.0, 1.0)
            val tonalShift = shadows * (1.0 - luminance).pow(2.0) + highlights * luminance.pow(2.0)
            red += tonalShift
            green += tonalShift
            blue += tonalShift
            red += whitePoint * red.coerceIn(0.0, 1.0).pow(2.0) - blackPoint * (1.0 - red.coerceIn(0.0, 1.0)).pow(2.0)
            green += whitePoint * green.coerceIn(0.0, 1.0).pow(2.0) - blackPoint * (1.0 - green.coerceIn(0.0, 1.0)).pow(2.0)
            blue += whitePoint * blue.coerceIn(0.0, 1.0).pow(2.0) - blackPoint * (1.0 - blue.coerceIn(0.0, 1.0)).pow(2.0)

            red = red.coerceIn(0.0, 1.0).pow(inverseGamma)
            green = green.coerceIn(0.0, 1.0).pow(inverseGamma)
            blue = blue.coerceIn(0.0, 1.0).pow(inverseGamma)

            red += temperature + tint * 0.5 + redBalance
            green += -tint + greenBalance
            blue += -temperature + tint * 0.5 + blueBalance
            red = red.coerceIn(0.0, 1.0)
            green = green.coerceIn(0.0, 1.0)
            blue = blue.coerceIn(0.0, 1.0)

            val hsv = rgbToHsv(red, green, blue)
            val vibranceFactor = if (vibrance >= 0.0) {
                1.0 + vibrance * (1.0 - hsv.saturation)
            } else {
                1.0 + vibrance
            }
            val saturationFactor = (1.0 + saturation + faded * 0.22) * vibranceFactor
            val converted = hsvToRgb(
                hue = wrapUnit(hsv.hue + hueRotation),
                saturation = (hsv.saturation * saturationFactor).coerceIn(0.0, 1.0),
                value = hsv.value,
            )
            red = converted.red
            green = converted.green
            blue = converted.blue

            // Guard the pixel contract even if future color math changes.
            luminance = luminance(red, green, blue)
            if (!luminance.isFinite()) {
                red = 0.0
                green = 0.0
                blue = 0.0
            }
            pixels[index] = argb(alpha(pixel), red * 255.0, green * 255.0, blue * 255.0)
        }
    }

    private fun refineEdges(
        source: IntArray,
        width: Int,
        height: Int,
        amount: Double,
        detailPreservation: Double,
    ): IntArray {
        if (width == 1 && height == 1) return source
        val output = IntArray(source.size)
        val threshold = 1.0 + detailPreservation * 3.0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val center = source[index]
                val centerAlpha = alpha(center)
                var redSum = 0.0
                var greenSum = 0.0
                var blueSum = 0.0
                var count = 0
                var redMinimum = 255
                var greenMinimum = 255
                var blueMinimum = 255
                var redMaximum = 0
                var greenMaximum = 0
                var blueMaximum = 0

                for (offsetY in -1..1) {
                    val neighborY = (y + offsetY).coerceIn(0, height - 1)
                    for (offsetX in -1..1) {
                        val neighborX = (x + offsetX).coerceIn(0, width - 1)
                        val neighbor = source[neighborY * width + neighborX]
                        if (abs(alpha(neighbor) - centerAlpha) > 8) continue
                        val neighborRed = red(neighbor)
                        val neighborGreen = green(neighbor)
                        val neighborBlue = blue(neighbor)
                        redSum += neighborRed
                        greenSum += neighborGreen
                        blueSum += neighborBlue
                        count++
                        redMinimum = min(redMinimum, neighborRed)
                        greenMinimum = min(greenMinimum, neighborGreen)
                        blueMinimum = min(blueMinimum, neighborBlue)
                        redMaximum = max(redMaximum, neighborRed)
                        greenMaximum = max(greenMaximum, neighborGreen)
                        blueMaximum = max(blueMaximum, neighborBlue)
                    }
                }
                if (count == 0) {
                    output[index] = center
                    continue
                }

                output[index] = argb(
                    centerAlpha,
                    sharpened(red(center), redSum / count, amount, threshold, redMinimum, redMaximum),
                    sharpened(green(center), greenSum / count, amount, threshold, greenMinimum, greenMaximum),
                    sharpened(blue(center), blueSum / count, amount, threshold, blueMinimum, blueMaximum),
                )
            }
        }
        return output
    }

    private fun sharpened(
        center: Int,
        blurred: Double,
        amount: Double,
        threshold: Double,
        localMinimum: Int,
        localMaximum: Int,
    ): Double {
        val detail = center - blurred
        if (abs(detail) < threshold) return center.toDouble()
        return (center + amount * detail).coerceIn(localMinimum.toDouble(), localMaximum.toDouble())
    }

    private fun rgbToHsv(red: Double, green: Double, blue: Double): Hsv {
        val maximum = max(red, max(green, blue))
        val minimum = min(red, min(green, blue))
        val delta = maximum - minimum
        val hue = when {
            delta == 0.0 -> 0.0
            maximum == red -> ((green - blue) / delta) / 6.0
            maximum == green -> (((blue - red) / delta) + 2.0) / 6.0
            else -> (((red - green) / delta) + 4.0) / 6.0
        }
        return Hsv(
            hue = wrapUnit(hue),
            saturation = if (maximum == 0.0) 0.0 else delta / maximum,
            value = maximum,
        )
    }

    private fun hsvToRgb(hue: Double, saturation: Double, value: Double): Rgb {
        if (saturation == 0.0) return Rgb(value, value, value)
        val sectorValue = wrapUnit(hue) * 6.0
        val sector = floor(sectorValue).toInt().coerceIn(0, 5)
        val fraction = sectorValue - sector
        val lower = value * (1.0 - saturation)
        val descending = value * (1.0 - saturation * fraction)
        val ascending = value * (1.0 - saturation * (1.0 - fraction))
        return when (sector) {
            0 -> Rgb(value, ascending, lower)
            1 -> Rgb(descending, value, lower)
            2 -> Rgb(lower, value, ascending)
            3 -> Rgb(lower, descending, value)
            4 -> Rgb(ascending, lower, value)
            else -> Rgb(value, lower, descending)
        }
    }

    private fun hasToneOrColorAdjustment(value: Adjustments): Boolean =
        value.exposure != 0f || value.brightness != 0f || value.contrast != 0f ||
            value.highlights != 0f || value.shadows != 0f || value.whitePoint != 0f ||
            value.blackPoint != 0f || value.gamma != 1f || value.temperature != 0f ||
            value.tint != 0f || value.saturation != 0f || value.vibrance != 0f ||
            value.hue != 0f || value.redBalance != 0f || value.greenBalance != 0f ||
            value.blueBalance != 0f || value.fadedColor != 0f

    private fun validateImage(pixels: IntArray, width: Int, height: Int) {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        val expected = width.toLong() * height.toLong()
        require(expected <= Int.MAX_VALUE && pixels.size == expected.toInt()) {
            "Pixel count does not match image dimensions"
        }
    }

    private fun mix(start: Double, end: Double, amount: Double): Double =
        start + (end - start) * amount

    private fun wrapUnit(value: Double): Double {
        val remainder = value % 1.0
        return if (remainder < 0.0) remainder + 1.0 else remainder
    }

    private fun luminance(red: Double, green: Double, blue: Double): Double =
        0.2126 * red + 0.7152 * green + 0.0722 * blue

    private fun alpha(pixel: Int): Int = pixel ushr 24 and 0xff
    private fun red(pixel: Int): Int = pixel ushr 16 and 0xff
    private fun green(pixel: Int): Int = pixel ushr 8 and 0xff
    private fun blue(pixel: Int): Int = pixel and 0xff

    private fun argb(alpha: Int, red: Double, green: Double, blue: Double): Int =
        (alpha.coerceIn(0, 255) shl 24) or
            (red.toChannel() shl 16) or
            (green.toChannel() shl 8) or
            blue.toChannel()

    private fun Double.toChannel(): Int = if (isFinite()) {
        (this + 0.5).toInt().coerceIn(0, 255)
    } else {
        0
    }

    private data class Hsv(val hue: Double, val saturation: Double, val value: Double)
    private data class Rgb(val red: Double, val green: Double, val blue: Double)
}
