package dev.localphoto.enhancer.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import dev.localphoto.core.TilePlanner
import dev.localphoto.enhancer.data.PhotoFailure
import dev.localphoto.enhancer.processing.ProcessingControl
import java.nio.FloatBuffer

internal class TileMemoryPressure(cause: Throwable) : RuntimeException(cause)
private class BackendInferenceFailure(val backend: String, cause: Throwable) : RuntimeException(cause)

class TiledSuperResolution(private val models: ModelManager) {
    suspend fun upscale(
        source: Bitmap,
        scale: Int,
        tileSize: Int,
        control: ProcessingControl,
        beforeWork: () -> Unit = {},
        progress: suspend (Float) -> Unit,
    ): Bitmap {
        require(scale in setOf(2, 4, 8))
        return try {
            upscaleOnce(source, scale, tileSize, control, beforeWork, progress)
        } catch (failure: BackendInferenceFailure) {
            control.check()
            if (!models.activateCpuFallback(failure.backend)) throw PhotoFailure("inference", failure)
            progress(0f)
            upscaleOnce(source, scale, tileSize, control, beforeWork, progress)
        }
    }

    private suspend fun upscaleOnce(
        source: Bitmap,
        scale: Int,
        tileSize: Int,
        control: ProcessingControl,
        beforeWork: () -> Unit,
        progress: suspend (Float) -> Unit,
    ): Bitmap {
        val outputWidth = try { Math.multiplyExact(source.width, scale) }
        catch (failure: ArithmeticException) { throw PhotoFailure("memory", failure) }
        val outputHeight = try { Math.multiplyExact(source.height, scale) }
        catch (failure: ArithmeticException) { throw PhotoFailure("memory", failure) }
        val output = try {
            Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        } catch (failure: OutOfMemoryError) {
            throw PhotoFailure("memory", failure)
        }
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        try {
            models.openSession(control).use { handle ->
                // TilePlanner's tileSize includes both halo regions, so clamping here also
                // guarantees every bitmap passed to the fixed-shape model fits 224x224.
                val tiles = TilePlanner.tiles(
                    source.width,
                    source.height,
                    tileSize.coerceAtMost(MODEL_INPUT_SIZE),
                    8,
                )
                for ((index, tile) in tiles.withIndex()) {
                    control.check()
                    beforeWork()
                    val input = try {
                        Bitmap.createBitmap(source, tile.left, tile.top, tile.width, tile.height)
                    } catch (failure: OutOfMemoryError) {
                        throw TileMemoryPressure(failure)
                    }
                    var neural: Bitmap? = null
                    try {
                        neural = infer(input, handle, control)
                        // The learned graph is 3x. Other requested dimensions are local resampling,
                        // explicitly documented rather than claimed as native 4x/8x models.
                        val x = tile.coreLeft - tile.left
                        val y = tile.coreTop - tile.top
                        canvas.drawBitmap(neural,
                            Rect(x * 3, y * 3, (x + tile.coreWidth) * 3, (y + tile.coreHeight) * 3),
                            Rect(tile.coreLeft * scale, tile.coreTop * scale,
                                (tile.coreLeft + tile.coreWidth) * scale, (tile.coreTop + tile.coreHeight) * scale), paint)
                    } finally { neural?.recycle(); if (input !== source) input.recycle() }
                    progress((index + 1f) / tiles.size)
                }
            }
            return output
        } catch (failure: Throwable) { output.recycle(); control.check(); throw failure }
    }

    private fun infer(input: Bitmap, handle: ModelSession, control: ProcessingControl): Bitmap {
        var chroma: Bitmap? = null
        try {
            require(input.width <= MODEL_INPUT_SIZE && input.height <= MODEL_INPUT_SIZE)
            val pixels = IntArray(input.width * input.height)
            input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)

            val paddingLeft = (MODEL_INPUT_SIZE - input.width) / 2
            val paddingTop = (MODEL_INPUT_SIZE - input.height) / 2
            val reflectedX = IntArray(MODEL_INPUT_SIZE) { x ->
                reflectedIndex(x - paddingLeft, input.width)
            }
            val luminance = FloatArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
            for (y in 0 until MODEL_INPUT_SIZE) {
                val sourceRow = reflectedIndex(y - paddingTop, input.height) * input.width
                val outputRow = y * MODEL_INPUT_SIZE
                for (x in 0 until MODEL_INPUT_SIZE) {
                    val p = pixels[sourceRow + reflectedX[x]]
                    luminance[outputRow + x] = (
                        0.299f * (p shr 16 and 255) +
                            0.587f * (p shr 8 and 255) +
                            0.114f * (p and 255)
                        ) / 255f
                }
            }
            val width = input.width * 3
            val height = input.height * 3
            chroma = Bitmap.createScaledBitmap(input, width, height, true)
            val resultPixels = IntArray(width * height)
            chroma.getPixels(resultPixels, 0, width, 0, 0, width, height)
            chroma.recycle()
            chroma = null
            OnnxTensor.createTensor(
                models.environment,
                FloatBuffer.wrap(luminance),
                longArrayOf(1, 1, MODEL_INPUT_SIZE.toLong(), MODEL_INPUT_SIZE.toLong()),
            ).use { tensor ->
                OrtSession.RunOptions().use { options ->
                    control.bind(options)
                    try {
                        handle.session.run(mapOf(handle.session.inputNames.first() to tensor), options).use { result ->
                            val output = result[0] as OnnxTensor
                            require(output.info.shape.contentEquals(
                                longArrayOf(1, 1, MODEL_OUTPUT_SIZE.toLong(), MODEL_OUTPUT_SIZE.toLong()),
                            ))
                            val buffer = output.floatBuffer
                            for (index in resultPixels.indices) {
                                val original = resultPixels[index]
                                val r = (original shr 16 and 255).toFloat()
                                val g = (original shr 8 and 255).toFloat()
                                val b = (original and 255).toFloat()
                                val before = 0.299f * r + 0.587f * g + 0.114f * b
                                val outputX = paddingLeft * MODEL_SCALE + index % width
                                val outputY = paddingTop * MODEL_SCALE + index / width
                                val predicted = buffer.get(outputY * MODEL_OUTPUT_SIZE + outputX) * 255f
                                require(predicted.isFinite())
                                // Limit large luminance excursions to preserve structure and reduce ringing.
                                val y = predicted.coerceIn((before - 48f).coerceAtLeast(0f), (before + 48f).coerceAtMost(255f))
                                val cb = -0.168736f * r - 0.331264f * g + 0.5f * b
                                val cr = 0.5f * r - 0.418688f * g - 0.081312f * b
                                val red = (y + 1.402f * cr).toInt().coerceIn(0, 255)
                                val green = (y - 0.344136f * cb - 0.714136f * cr).toInt().coerceIn(0, 255)
                                val blue = (y + 1.772f * cb).toInt().coerceIn(0, 255)
                                resultPixels[index] = (original and -0x1000000) or (red shl 16) or (green shl 8) or blue
                            }
                        }
                    } finally { control.release(options) }
                }
            }
            control.check()
            return Bitmap.createBitmap(resultPixels, width, height, Bitmap.Config.ARGB_8888)
        } catch (failure: Throwable) {
            control.check()
            if (isMemoryPressure(failure)) throw TileMemoryPressure(failure)
            if (failure is TileMemoryPressure || failure is BackendInferenceFailure) throw failure
            throw BackendInferenceFailure(handle.backend, failure)
        } finally {
            chroma?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private fun reflectedIndex(index: Int, size: Int): Int {
        if (size == 1) return 0
        val period = (size - 1) * 2
        val wrapped = ((index % period) + period) % period
        return if (wrapped < size) wrapped else period - wrapped
    }

    private fun isMemoryPressure(failure: Throwable): Boolean {
        var current: Throwable? = failure
        while (current != null) {
            if (current is OutOfMemoryError) return true
            val message = current.message.orEmpty().lowercase()
            if ("out of memory" in message || "failed to allocate" in message) return true
            current = current.cause
        }
        return false
    }

    private companion object {
        const val MODEL_INPUT_SIZE = 224
        const val MODEL_SCALE = 3
        const val MODEL_OUTPUT_SIZE = MODEL_INPUT_SIZE * MODEL_SCALE
    }
}
