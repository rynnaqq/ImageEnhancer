package dev.localphoto.enhancer.ai

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import dev.localphoto.core.RestorationPixels
import dev.localphoto.core.RestorationSettings
import dev.localphoto.enhancer.data.PhotoFailure
import dev.localphoto.enhancer.processing.ProcessingControl
import dev.localphoto.enhancer.processing.ProcessingStopped
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class RepairResult(val bitmap: Bitmap, val mask: BooleanArray, val selectedPixels: Int)

/** Learned chroma prediction and masked inpainting; geometry and original alpha are retained. */
class NeuralRestoration(private val context: Context, private val models: ModelManager) {
    suspend fun colorize(source: Bitmap, strength: Float, control: ProcessingControl,
        beforeWork: () -> Unit = {}, progress: suspend (Float) -> Unit): Bitmap {
        control.check()
        if (strength <= 0f) return source.copy(Bitmap.Config.ARGB_8888, true)
        beforeWork()
        val size = COLOR_SIZE
        val reduced = Bitmap.createScaledBitmap(source, size, size, true)
        val sample = IntArray(size * size)
        try { reduced.getPixels(sample, 0, size, 0, 0, size, size) }
        finally { if (reduced !== source) reduced.recycle() }
        val tensor = FloatArray(sample.size * 3)
        for (index in sample.indices) {
            if (index % size == 0) control.check()
            val lightness = RestorationPixels.rgbToLab(sample[index])[0]
            val neutral = RestorationPixels.labToArgb(lightness, 0f, 0f)
            tensor[index] = (neutral ushr 16 and 255) / 255f
            tensor[index + sample.size] = (neutral ushr 8 and 255) / 255f
            tensor[index + sample.size * 2] = (neutral and 255) / 255f
        }
        progress(0.1f)
        val chroma = infer("color-ddcolor", mapOf("input" to tensor),
            mapOf("input" to longArrayOf(1, 3, size.toLong(), size.toLong())),
            longArrayOf(1, 2, size.toLong(), size.toLong()), control)
        progress(0.8f)
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        try {
            val row = IntArray(source.width)
            val amount = strength.coerceIn(0f, 100f) / 100f
            for (y in 0 until source.height) {
                beforeWork()
                source.getPixels(row, 0, source.width, 0, y, source.width, 1)
                val modelY = (y + 0.5f) * size / source.height - 0.5f
                for (x in row.indices) {
                    val original = row[x]
                    if (original ushr 24 == 0) continue
                    val lab = RestorationPixels.rgbToLab(original)
                    val modelX = (x + 0.5f) * size / source.width - 0.5f
                    val a = samplePlane(chroma, 0, size, modelX, modelY)
                    val b = samplePlane(chroma, size * size, size, modelX, modelY)
                    row[x] = RestorationPixels.labToArgb(lab[0],
                        lab[1] + (a - lab[1]) * amount, lab[2] + (b - lab[2]) * amount,
                        original ushr 24)
                }
                output.setPixels(row, 0, source.width, 0, y, source.width, 1)
            }
            progress(1f)
            return output
        } catch (failure: Throwable) { output.recycle(); throw failure }
    }

    suspend fun repair(source: Bitmap, settings: RestorationSettings, control: ProcessingControl,
        beforeWork: () -> Unit = {}, progress: suspend (Float) -> Unit): RepairResult {
        control.check()
        val options = settings.normalized()
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        beforeWork()
        val mask = RestorationPixels.rasterMask(source.width, source.height, options.maskStrokes)
        if (options.scratchRepair) {
            // Run the conservative detector at bounded resolution; the neural network fills its mask.
            val ratio = min(1f, 768f / max(source.width, source.height))
            val width = (source.width * ratio).roundToInt().coerceAtLeast(1)
            val height = (source.height * ratio).roundToInt().coerceAtLeast(1)
            val preview = Bitmap.createScaledBitmap(source, width, height, true)
            val scratchPixels = IntArray(width * height)
            try { preview.getPixels(scratchPixels, 0, width, 0, 0, width, height) }
            finally { if (preview !== source) preview.recycle() }
            val detected = RestorationPixels.detectScratches(scratchPixels, width, height)
            for (y in 0 until source.height) {
                control.check()
                for (x in 0 until source.width) {
                    val sx = (x.toLong() * width / source.width).toInt().coerceAtMost(width - 1)
                    val sy = (y.toLong() * height / source.height).toInt().coerceAtMost(height - 1)
                    if (detected[sy * width + sx]) mask[y * source.width + x] = true
                }
            }
        }
        for (index in pixels.indices) if (pixels[index] ushr 24 == 0) mask[index] = false
        val count = mask.count { it }
        if (count == 0 || options.repairStrength == 0f) {
            progress(1f)
            return RepairResult(source.copy(Bitmap.Config.ARGB_8888, true), BooleanArray(mask.size), 0)
        }
        if (count.toLong() * 100 > mask.size.toLong() * 45) throw PhotoFailure("repair_mask_large")
        var left = source.width - 1
        var top = source.height - 1
        var right = 0
        var bottom = 0
        for (index in mask.indices) if (mask[index]) {
            val x = index % source.width
            val y = index / source.width
            left = min(left, x); right = max(right, x)
            top = min(top, y); bottom = max(bottom, y)
        }
        val margin = max(48, max(right - left + 1, bottom - top + 1) / 2)
        left = (left - margin).coerceAtLeast(0)
        top = (top - margin).coerceAtLeast(0)
        right = (right + margin).coerceAtMost(source.width - 1)
        bottom = (bottom + margin).coerceAtMost(source.height - 1)
        val cropWidth = right - left + 1
        val cropHeight = bottom - top + 1
        val cropped = Bitmap.createBitmap(source, left, top, cropWidth, cropHeight)
        val reduced = Bitmap.createScaledBitmap(cropped, REPAIR_SIZE, REPAIR_SIZE, true)
        val inputPixels = IntArray(REPAIR_SIZE * REPAIR_SIZE)
        try { reduced.getPixels(inputPixels, 0, REPAIR_SIZE, 0, 0, REPAIR_SIZE, REPAIR_SIZE) }
        finally {
            if (reduced !== cropped && reduced !== source) reduced.recycle()
            if (cropped !== source) cropped.recycle()
        }
        val rawMask = BooleanArray(inputPixels.size)
        // Max-pooling the source mask keeps single-pixel scratches from disappearing on resize.
        for (y in top..bottom) {
            control.check()
            for (x in left..right) if (mask[y * source.width + x]) {
                val mx = ((x - left).toLong() * REPAIR_SIZE / cropWidth).toInt().coerceAtMost(REPAIR_SIZE - 1)
                val my = ((y - top).toLong() * REPAIR_SIZE / cropHeight).toInt().coerceAtMost(REPAIR_SIZE - 1)
                rawMask[my * REPAIR_SIZE + mx] = true
            }
        }
        val modelMask = FloatArray(inputPixels.size)
        for (index in rawMask.indices) if (rawMask[index]) {
            val x = index % REPAIR_SIZE
            val y = index / REPAIR_SIZE
            for (my in max(0, y - 2)..min(REPAIR_SIZE - 1, y + 2))
                for (mx in max(0, x - 2)..min(REPAIR_SIZE - 1, x + 2)) modelMask[my * REPAIR_SIZE + mx] = 1f
        }
        val input = FloatArray(inputPixels.size * 3)
        for (index in inputPixels.indices) if (modelMask[index] == 0f) {
            input[index] = (inputPixels[index] ushr 16 and 255) / 255f
            input[index + inputPixels.size] = (inputPixels[index] ushr 8 and 255) / 255f
            input[index + inputPixels.size * 2] = (inputPixels[index] and 255) / 255f
        }
        progress(0.15f)
        val generated = infer("repair-lama", mapOf("image" to input, "mask" to modelMask), mapOf(
            "image" to longArrayOf(1, 3, REPAIR_SIZE.toLong(), REPAIR_SIZE.toLong()),
            "mask" to longArrayOf(1, 1, REPAIR_SIZE.toLong(), REPAIR_SIZE.toLong())),
            longArrayOf(1, 3, REPAIR_SIZE.toLong(), REPAIR_SIZE.toLong()), control)
        progress(0.85f)
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        try {
            val row = IntArray(cropWidth)
            val amount = options.repairStrength / 100f
            for (y in top..bottom) {
                beforeWork()
                for (x in left..right) {
                    val index = y * source.width + x
                    val original = pixels[index]
                    row[x - left] = if (!mask[index]) original else {
                        val mx = (x - left + 0.5f) * REPAIR_SIZE / cropWidth - 0.5f
                        val my = (y - top + 0.5f) * REPAIR_SIZE / cropHeight - 0.5f
                        val r = samplePlane(generated, 0, REPAIR_SIZE, mx, my).coerceIn(0f, 255f)
                        val g = samplePlane(generated, inputPixels.size, REPAIR_SIZE, mx, my).coerceIn(0f, 255f)
                        val b = samplePlane(generated, inputPixels.size * 2, REPAIR_SIZE, mx, my).coerceIn(0f, 255f)
                        (original ushr 24 shl 24) or
                            (mix(original ushr 16 and 255, r, amount) shl 16) or
                            (mix(original ushr 8 and 255, g, amount) shl 8) or mix(original and 255, b, amount)
                    }
                }
                output.setPixels(row, 0, cropWidth, left, y, cropWidth, 1)
            }
            progress(1f)
            return RepairResult(output, mask, count)
        } catch (failure: Throwable) { output.recycle(); throw failure }
    }

    private fun infer(id: String, values: Map<String, FloatArray>, shapes: Map<String, LongArray>,
        outputShape: LongArray, control: ProcessingControl): FloatArray {
        control.check()
        val tensors = linkedMapOf<String, OnnxTensor>()
        try {
            models.openModuleSession(id, control).use { handle ->
                if (handle.session.inputNames != values.keys) throw PhotoFailure("model_shape")
                for ((name, data) in values) tensors[name] = OnnxTensor.createTensor(
                    models.environment, FloatBuffer.wrap(data), shapes.getValue(name))
                OrtSession.RunOptions().use { options ->
                    control.bind(options)
                    try {
                        handle.session.run(tensors, options).use { result ->
                            control.check()
                            val tensor = result[0] as? OnnxTensor ?: throw PhotoFailure("model_shape")
                            if (!tensor.info.shape.contentEquals(outputShape)) throw PhotoFailure("model_shape")
                            val buffer = tensor.floatBuffer
                            val output = FloatArray(buffer.remaining())
                            buffer.get(output)
                            if (output.any { !it.isFinite() }) throw PhotoFailure("inference")
                            models.markInferenceReady(id)
                            return output
                        }
                    } finally { control.release(options) }
                }
            }
        } catch (failure: Throwable) {
            control.check()
            if (failure is ProcessingStopped) throw failure
            val code = when (failure) {
                is PhotoFailure -> failure.code
                is OutOfMemoryError -> "module_memory"
                else -> "inference"
            }
            models.markInferenceFailed(id, code)
            throw if (failure is PhotoFailure) failure else PhotoFailure(code, failure)
        } finally { tensors.values.forEach { it.close() } }
    }

    private fun samplePlane(values: FloatArray, offset: Int, size: Int, x: Float, y: Float): Float {
        val px = x.coerceIn(0f, size - 1f)
        val py = y.coerceIn(0f, size - 1f)
        val x0 = px.toInt(); val y0 = py.toInt()
        val x1 = min(x0 + 1, size - 1); val y1 = min(y0 + 1, size - 1)
        val dx = px - x0; val dy = py - y0
        val a = values[offset + y0 * size + x0] * (1 - dx) + values[offset + y0 * size + x1] * dx
        val b = values[offset + y1 * size + x0] * (1 - dx) + values[offset + y1 * size + x1] * dx
        return a * (1 - dy) + b * dy
    }

    private fun mix(original: Int, generated: Float, amount: Float): Int =
        (original + (generated - original) * amount).roundToInt().coerceIn(0, 255)

    private companion object { const val COLOR_SIZE = 512; const val REPAIR_SIZE = 512 }
}
