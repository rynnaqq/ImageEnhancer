package dev.localphoto.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object ImageAnalyzer {
    fun analyze(pixels: IntArray, width: Int, height: Int): ImageMetrics {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        val expected = width.toLong() * height.toLong()
        require(expected <= Int.MAX_VALUE && pixels.size == expected.toInt()) {
            "Pixel count does not match image dimensions"
        }

        var luminanceSum = 0.0
        var luminanceSquaredSum = 0.0
        var saturationSum = 0.0
        var channelSpreadSum = 0.0
        var redSum = 0.0
        var greenSum = 0.0
        var blueSum = 0.0

        for (pixel in pixels) {
            val red = (pixel ushr 16 and 0xff) / 255.0
            val green = (pixel ushr 8 and 0xff) / 255.0
            val blue = (pixel and 0xff) / 255.0
            val luminance = luminance(red, green, blue)
            val maximum = max(red, max(green, blue))
            val minimum = min(red, min(green, blue))

            luminanceSum += luminance
            luminanceSquaredSum += luminance * luminance
            saturationSum += if (maximum == 0.0) 0.0 else (maximum - minimum) / maximum
            channelSpreadSum += maximum - minimum
            redSum += red
            greenSum += green
            blueSum += blue
        }

        val count = pixels.size.toDouble()
        val averageLuminance = luminanceSum / count
        val variance = (luminanceSquaredSum / count - averageLuminance * averageLuminance).coerceAtLeast(0.0)
        val averageRed = redSum / count
        val averageGreen = greenSum / count
        val averageBlue = blueSum / count
        val cast = max(averageRed, max(averageGreen, averageBlue)) -
            min(averageRed, min(averageGreen, averageBlue))

        val detail = detailMetrics(pixels, width, height)
        return ImageMetrics(
            width = width,
            height = height,
            luminance = averageLuminance.toFloat().coerceIn(0f, 1f),
            contrast = sqrt(variance).toFloat().coerceIn(0f, 1f),
            noise = detail.noise.toFloat().coerceIn(0f, 1f),
            sharpness = detail.sharpness.toFloat().coerceIn(0f, 1f),
            saturation = (saturationSum / count).toFloat().coerceIn(0f, 1f),
            colorCast = cast.toFloat().coerceIn(0f, 1f),
            monochrome = channelSpreadSum / count < 0.025,
        )
    }

    private fun detailMetrics(pixels: IntArray, width: Int, height: Int): DetailMetrics {
        if (width < 3 || height < 3) return DetailMetrics(0.0, 0.0)
        var noiseSum = 0.0
        var noiseSamples = 0
        var sharpnessSum = 0.0
        var sharpnessSamples = 0

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = pixelLuminance(pixels[y * width + x])
                val left = pixelLuminance(pixels[y * width + x - 1])
                val right = pixelLuminance(pixels[y * width + x + 1])
                val up = pixelLuminance(pixels[(y - 1) * width + x])
                val down = pixelLuminance(pixels[(y + 1) * width + x])
                val horizontalGradient = abs(right - left) * 0.5
                val verticalGradient = abs(down - up) * 0.5
                sharpnessSum += max(horizontalGradient, verticalGradient)
                sharpnessSamples++

                val localEdge = max(
                    max(abs(center - left), abs(center - right)),
                    max(abs(center - up), abs(center - down)),
                )
                if (localEdge < 0.22) {
                    val neighborAverage = (left + right + up + down) * 0.25
                    noiseSum += abs(center - neighborAverage)
                    noiseSamples++
                }
            }
        }
        return DetailMetrics(
            noise = if (noiseSamples == 0) 0.0 else noiseSum / noiseSamples,
            sharpness = if (sharpnessSamples == 0) 0.0 else sharpnessSum / sharpnessSamples,
        )
    }

    private fun pixelLuminance(pixel: Int): Double = luminance(
        (pixel ushr 16 and 0xff) / 255.0,
        (pixel ushr 8 and 0xff) / 255.0,
        (pixel and 0xff) / 255.0,
    )

    private fun luminance(red: Double, green: Double, blue: Double): Double =
        0.2126 * red + 0.7152 * green + 0.0722 * blue

    private data class DetailMetrics(val noise: Double, val sharpness: Double)
}
