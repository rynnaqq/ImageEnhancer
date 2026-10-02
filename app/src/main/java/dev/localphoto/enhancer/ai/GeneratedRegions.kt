package dev.localphoto.enhancer.ai

import android.graphics.Bitmap
import dev.localphoto.core.TransformSettings
import dev.localphoto.enhancer.data.ImageFiles
import dev.localphoto.enhancer.data.PhotoFailure
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Private result/checkpoint companions record synthesized regions in image coordinates. */
class GeneratedRegions(private val files: ImageFiles) {
    fun companion(image: File): File = files.owned(File(image.parentFile, image.name + ".regions.png"))

    fun read(image: File, transform: TransformSettings? = null, required: Boolean = false): Bitmap? {
        val path = companion(image)
        if (!path.isFile) {
            if (required) throw PhotoFailure("resume_regions")
            return null
        }
        val decoded = files.decodeResult(path)
        if (decoded.width > 1024 || decoded.height > 1024) {
            decoded.recycle()
            throw PhotoFailure("model_integrity")
        }
        return try {
            if (transform == null) decoded else files.transform(decoded, transform).also {
                if (it !== decoded) decoded.recycle()
            }
        } catch (failure: Throwable) { if (!decoded.isRecycled) decoded.recycle(); throw failure }
    }

    /** Max-pool selections so even a thin generated scratch remains protected after downsampling. */
    fun merge(previous: Bitmap?, mask: BooleanArray, width: Int, height: Int): Bitmap {
        require(mask.size == width * height)
        val ratio = min(1f, 512f / max(width, height))
        val targetWidth = (width * ratio).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * ratio).roundToInt().coerceAtLeast(1)
        val pixels = IntArray(targetWidth * targetHeight) { 0xff000000.toInt() }
        if (previous != null) {
            val resized = Bitmap.createScaledBitmap(previous, targetWidth, targetHeight, false)
            try { resized.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight) }
            finally { if (resized !== previous) resized.recycle() }
        }
        for (index in mask.indices) if (mask[index]) {
            val x = index % width
            val y = index / width
            val targetX = (x.toLong() * targetWidth / width).toInt().coerceAtMost(targetWidth - 1)
            val targetY = (y.toLong() * targetHeight / height).toInt().coerceAtMost(targetHeight - 1)
            // Expand by one tracking pixel to cover resampling and transformation boundaries.
            for (py in max(0, targetY - 1)..min(targetHeight - 1, targetY + 1))
                for (px in max(0, targetX - 1)..min(targetWidth - 1, targetX + 1)) pixels[py * targetWidth + px] = -1
        }
        return Bitmap.createBitmap(pixels, targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    }

    fun write(image: File, regions: Bitmap?) {
        if (regions != null) files.atomicPng(regions, companion(image))
    }
}
