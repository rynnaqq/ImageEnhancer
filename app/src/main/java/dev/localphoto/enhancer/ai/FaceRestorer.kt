package dev.localphoto.enhancer.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import dev.localphoto.enhancer.data.PhotoFailure
import dev.localphoto.enhancer.processing.ProcessingControl
import dev.localphoto.enhancer.processing.ProcessingStopped
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class FaceRestorationResult(
    val bitmap: Bitmap,
    val detectedFaces: Int,
    val restoredFaces: Int,
    val protectedFaces: Int,
    val detailPreservedFaces: Int = 0,
)

internal data class YunetDetection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float,
    val landmarks: FloatArray,
)

/** Local neural face-detail enhancement. Generated detail is blended conservatively, not identity recovery. */
class FaceRestorer(private val models: ModelManager) {
    suspend fun restore(
        source: Bitmap,
        strength: Float,
        protectedMask: Bitmap?,
        control: ProcessingControl,
        beforeWork: () -> Unit = {},
        progress: suspend (Float) -> Unit,
    ): FaceRestorationResult {
        val normalizedStrength = if (strength.isFinite()) strength.coerceIn(0f, 100f) else 0f
        val output = copyArgb(source)
        if (normalizedStrength == 0f) {
            progress(1f)
            return FaceRestorationResult(output, 0, 0, 0)
        }
        try {
            control.check()
            beforeWork()
            val detections = runModel(YUNET_MODEL, control) {
                models.openModuleSession(YUNET_MODEL, control).use { handle ->
                    detectFaces(source, handle, control)
                }.also { models.markInferenceReady(YUNET_MODEL) }
            }
            control.check()
            if (detections.isEmpty()) {
                progress(1f)
                return FaceRestorationResult(output, 0, 0, 0)
            }

            val protected = BooleanArray(detections.size) { index ->
                protectedMask?.let { maskContainsFace(it, source.width, source.height, detections[index]) } == true
            }
            val protectedCount = protected.count { it }
            val detailPreserved = BooleanArray(detections.size)
            val work = linkedMapOf<Int, Matrix>()
            for (index in detections.indices) {
                control.check()
                if (protected[index]) continue
                val alignment = similarityMatrix(detections[index].landmarks, ARCFACE_TEMPLATE_96) ?: continue
                if (preservesExistingFaceDetail(alignment)) {
                    detailPreserved[index] = true
                } else if (work.size < MAX_RESTORED_FACES) {
                    work[index] = alignment
                }
            }
            val detailPreservedCount = detailPreserved.count { it }
            if (work.isEmpty()) {
                control.check()
                progress(1f)
                return FaceRestorationResult(output, detections.size, 0, protectedCount, detailPreservedCount)
            }

            control.check()
            beforeWork()
            var restored = 0
            runModel(FACE_MODEL, control) {
                models.openModuleSession(FACE_MODEL, control).use { handle ->
                    for (index in detections.indices) {
                        control.check()
                        val alignment = work[index]
                        if (alignment != null) {
                            beforeWork()
                            val restoredFace = inferFace(source, alignment, handle, control)
                            try {
                                blendFace(output, restoredFace, alignment, detections[index], normalizedStrength)
                                restored++
                            } finally {
                                restoredFace.recycle()
                            }
                        }
                        progress((index + 1f) / detections.size)
                    }
                }
                models.markInferenceReady(FACE_MODEL)
            }
            control.check()
            return FaceRestorationResult(output, detections.size, restored, protectedCount, detailPreservedCount)
        } catch (failure: Throwable) {
            output.recycle()
            throw failure
        }
    }

    private fun detectFaces(source: Bitmap, handle: ModelSession, control: ProcessingControl): List<YunetDetection> {
        val longest = max(source.width, source.height)
        if (longest <= 0) throw PhotoFailure("decode")
        val imageScale = DETECTOR_LONG_SIDE.toFloat() / longest
        val resizedWidth = max(1, (source.width * imageScale).roundToInt())
        val resizedHeight = max(1, (source.height * imageScale).roundToInt())
        val inputWidth = ceil(resizedWidth / 32.0).toInt() * 32
        val inputHeight = ceil(resizedHeight / 32.0).toInt() * 32
        val resized = try {
            Bitmap.createScaledBitmap(source, resizedWidth, resizedHeight, true)
        } catch (failure: OutOfMemoryError) {
            throw PhotoFailure("memory", failure)
        }
        try {
            val pixels = IntArray(resizedWidth * resizedHeight)
            resized.getPixels(pixels, 0, resizedWidth, 0, 0, resizedWidth, resizedHeight)
            val planeSize = inputWidth * inputHeight
            val input = FloatArray(planeSize * 3)
            for (y in 0 until resizedHeight) {
                val inputRow = y * inputWidth
                val pixelRow = y * resizedWidth
                for (x in 0 until resizedWidth) {
                    val pixel = pixels[pixelRow + x]
                    val target = inputRow + x
                    input[target] = (pixel and 0xff).toFloat()
                    input[planeSize + target] = (pixel ushr 8 and 0xff).toFloat()
                    input[planeSize * 2 + target] = (pixel ushr 16 and 0xff).toFloat()
                }
            }
            OnnxTensor.createTensor(
                models.environment,
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong()),
            ).use { tensor ->
                runSession(handle, mapOf(handle.session.inputNames.first() to tensor), control).use { result ->
                    val candidates = mutableListOf<YunetDetection>()
                    for (stride in YUNET_STRIDES) {
                        val featureWidth = inputWidth / stride
                        val featureHeight = inputHeight / stride
                        val count = featureWidth * featureHeight
                        candidates += decodeYunetHead(
                            classification = result.floatValues("cls_$stride", count),
                            objectness = result.floatValues("obj_$stride", count),
                            boxes = result.floatValues("bbox_$stride", count * 4),
                            landmarks = result.floatValues("kps_$stride", count * 10),
                            stride = stride,
                            featureWidth = featureWidth,
                            featureHeight = featureHeight,
                            imageScale = imageScale,
                            threshold = DETECTION_THRESHOLD,
                        )
                    }
                    return nonMaximumSuppression(
                        candidates.mapNotNull { it.clipped(source.width, source.height) },
                        NMS_THRESHOLD,
                    )
                }
            }
        } finally {
            if (resized !== source) resized.recycle()
        }
    }

    private fun inferFace(
        source: Bitmap,
        alignment: Matrix,
        handle: ModelSession,
        control: ProcessingControl,
    ): Bitmap {
        val aligned = createBitmap(FACE_INPUT_SIZE, FACE_INPUT_SIZE)
        try {
            aligned.eraseColor(Color.BLACK)
            Canvas(aligned).drawBitmap(source, alignment, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            val pixels = IntArray(FACE_INPUT_SIZE * FACE_INPUT_SIZE)
            aligned.getPixels(pixels, 0, FACE_INPUT_SIZE, 0, 0, FACE_INPUT_SIZE, FACE_INPUT_SIZE)
            val planeSize = pixels.size
            val input = FloatArray(planeSize * 3)
            for (index in pixels.indices) {
                val pixel = pixels[index]
                input[index] = (pixel ushr 16 and 0xff) / 255f
                input[planeSize + index] = (pixel ushr 8 and 0xff) / 255f
                input[planeSize * 2 + index] = (pixel and 0xff) / 255f
            }
            OnnxTensor.createTensor(
                models.environment,
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, FACE_INPUT_SIZE.toLong(), FACE_INPUT_SIZE.toLong()),
            ).use { tensor ->
                runSession(handle, mapOf(handle.session.inputNames.first() to tensor), control).use { result ->
                    val output = result[0] as? OnnxTensor ?: throw PhotoFailure("model_shape")
                    if (!output.info.shape.contentEquals(
                            longArrayOf(1, 3, FACE_OUTPUT_SIZE.toLong(), FACE_OUTPUT_SIZE.toLong()),
                        )
                    ) throw PhotoFailure("model_shape")
                    val values = output.floatBuffer
                    val expected = FACE_OUTPUT_SIZE * FACE_OUTPUT_SIZE * 3
                    if (values.remaining() != expected) throw PhotoFailure("model_shape")
                    val channels = FloatArray(expected)
                    values.get(channels)
                    if (channels.any { !it.isFinite() }) throw PhotoFailure("inference")
                    val outputPlane = FACE_OUTPUT_SIZE * FACE_OUTPUT_SIZE
                    val argb = IntArray(outputPlane)
                    for (index in argb.indices) {
                        val red = (channels[index].coerceIn(0f, 1f) * 255f).roundToInt()
                        val green = (channels[outputPlane + index].coerceIn(0f, 1f) * 255f).roundToInt()
                        val blue = (channels[outputPlane * 2 + index].coerceIn(0f, 1f) * 255f).roundToInt()
                        argb[index] = Color.argb(255, red, green, blue)
                    }
                    return try {
                        Bitmap.createBitmap(argb, FACE_OUTPUT_SIZE, FACE_OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
                    } catch (failure: OutOfMemoryError) {
                        throw PhotoFailure("memory", failure)
                    }
                }
            }
        } finally {
            aligned.recycle()
        }
    }

    private fun blendFace(
        output: Bitmap,
        restored: Bitmap,
        alignment: Matrix,
        face: YunetDetection,
        strength: Float,
    ) {
        val left = floor(face.left).toInt().coerceIn(0, output.width - 1)
        val top = floor(face.top).toInt().coerceIn(0, output.height - 1)
        val right = ceil(face.right).toInt().coerceIn(left + 1, output.width)
        val bottom = ceil(face.bottom).toInt().coerceIn(top + 1, output.height)
        val width = right - left
        val height = bottom - top
        val warped = createBitmap(width, height)
        try {
            warped.eraseColor(Color.TRANSPARENT)
            val patchToRoi = inverseOutputMatrix(alignment, left, top) ?: return
            Canvas(warped).drawBitmap(
                restored,
                patchToRoi,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            val originals = IntArray(width * height)
            val generated = IntArray(width * height)
            output.getPixels(originals, 0, width, left, top, width, height)
            warped.getPixels(generated, 0, width, 0, 0, width, height)
            val centerX = (face.left + face.right) * 0.5f
            val centerY = (face.top + face.bottom) * 0.5f
            val radiusX = max(1f, (face.right - face.left) * 0.5f)
            val radiusY = max(1f, (face.bottom - face.top) * 0.5f)
            val baseStrength = strength / 100f
            for (index in originals.indices) {
                val x = left + index % width + 0.5f
                val y = top + index / width + 0.5f
                val dx = (x - centerX) / radiusX
                val dy = (y - centerY) / radiusY
                val distance = sqrt(dx * dx + dy * dy)
                val feather = when {
                    distance <= 0.72f -> 1f
                    distance >= 1f -> 0f
                    else -> {
                        val value = (1f - distance) / 0.28f
                        value * value * (3f - 2f * value)
                    }
                }
                val generatedAlpha = generated[index] ushr 24 and 0xff
                val amount = baseStrength * feather * generatedAlpha / 255f
                if (amount <= 0f) continue
                val original = originals[index]
                val neural = generated[index]
                val red = mix(original ushr 16 and 0xff, neural ushr 16 and 0xff, amount)
                val green = mix(original ushr 8 and 0xff, neural ushr 8 and 0xff, amount)
                val blue = mix(original and 0xff, neural and 0xff, amount)
                originals[index] = (original and -0x1000000) or (red shl 16) or (green shl 8) or blue
            }
            output.setPixels(originals, 0, width, left, top, width, height)
        } finally {
            warped.recycle()
        }
    }

    private fun inverseOutputMatrix(alignment: Matrix, roiLeft: Int, roiTop: Int): Matrix? {
        val values = FloatArray(9)
        alignment.getValues(values)
        val a = values[Matrix.MSCALE_X]
        val negativeB = values[Matrix.MSKEW_X]
        val b = values[Matrix.MSKEW_Y]
        val tx = values[Matrix.MTRANS_X]
        val ty = values[Matrix.MTRANS_Y]
        if (!a.isFinite() || !b.isFinite() || !negativeB.isFinite() ||
            !tx.isFinite() || !ty.isFinite() || kotlin.math.abs(negativeB + b) > 0.001f
        ) return null
        val determinant = a * a + b * b
        if (determinant <= 1e-8f) return null
        val outputScale = FACE_OUTPUT_SIZE.toFloat() / FACE_INPUT_SIZE
        return Matrix().apply {
            setValues(
                floatArrayOf(
                    a / (outputScale * determinant),
                    b / (outputScale * determinant),
                    (-a * tx - b * ty) / determinant - roiLeft,
                    -b / (outputScale * determinant),
                    a / (outputScale * determinant),
                    (b * tx - a * ty) / determinant - roiTop,
                    0f,
                    0f,
                    1f,
                ),
            )
        }
    }

    private fun maskContainsFace(
        mask: Bitmap,
        sourceWidth: Int,
        sourceHeight: Int,
        face: YunetDetection,
    ): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0 || mask.width <= 0 || mask.height <= 0) return false
        val left = floor(face.left / sourceWidth * mask.width).toInt().coerceIn(0, mask.width - 1)
        val top = floor(face.top / sourceHeight * mask.height).toInt().coerceIn(0, mask.height - 1)
        val right = ceil(face.right / sourceWidth * mask.width).toInt().coerceIn(left + 1, mask.width)
        val bottom = ceil(face.bottom / sourceHeight * mask.height).toInt().coerceIn(top + 1, mask.height)
        val width = right - left
        val pixels = IntArray(width)
        for (y in top until bottom) {
            mask.getPixels(pixels, 0, width, left, y, width, 1)
            if (pixels.any { pixel ->
                    (pixel ushr 24 and 0xff) > 0 &&
                        (pixel ushr 16 and 0xff) + (pixel ushr 8 and 0xff) + (pixel and 0xff) >= 384
                }
            ) return true
        }
        return false
    }

    private fun runSession(
        handle: ModelSession,
        inputs: Map<String, OnnxTensor>,
        control: ProcessingControl,
    ): OrtSession.Result {
        OrtSession.RunOptions().use { options ->
            control.bind(options)
            try {
                return handle.session.run(inputs, options)
            } finally {
                control.release(options)
            }
        }
    }

    private inline fun <T> runModel(id: String, control: ProcessingControl, operation: () -> T): T {
        try {
            return operation()
        } catch (failure: ProcessingStopped) {
            throw failure
        } catch (failure: Throwable) {
            control.check()
            if (isMemoryPressure(failure)) throw PhotoFailure("memory", failure)
            val code = (failure as? PhotoFailure)?.code
            if (code in setOf("storage", "thermal", "decode")) throw failure
            val modelCode = code ?: "inference"
            models.markInferenceFailed(id, modelCode)
            throw if (failure is PhotoFailure) failure else PhotoFailure("inference", failure)
        }
    }

    private fun copyArgb(source: Bitmap): Bitmap = try {
        source.copy(Bitmap.Config.ARGB_8888, true) ?: throw PhotoFailure("memory")
    } catch (failure: OutOfMemoryError) {
        throw PhotoFailure("memory", failure)
    }

    private fun createBitmap(width: Int, height: Int): Bitmap = try {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    } catch (failure: OutOfMemoryError) {
        throw PhotoFailure("memory", failure)
    }

    private fun OrtSession.Result.floatValues(name: String, expected: Int): FloatArray {
        val tensor = get(name).orElse(null) as? OnnxTensor ?: throw PhotoFailure("model_shape")
        val buffer = tensor.floatBuffer
        if (buffer.remaining() != expected) throw PhotoFailure("model_shape")
        return FloatArray(expected).also { values ->
            buffer.get(values)
            if (values.any { !it.isFinite() }) throw PhotoFailure("inference")
        }
    }

    private fun YunetDetection.clipped(width: Int, height: Int): YunetDetection? {
        val clippedLeft = left.coerceIn(0f, width.toFloat())
        val clippedTop = top.coerceIn(0f, height.toFloat())
        val clippedRight = right.coerceIn(0f, width.toFloat())
        val clippedBottom = bottom.coerceIn(0f, height.toFloat())
        if (clippedRight - clippedLeft < 2f || clippedBottom - clippedTop < 2f) return null
        return copy(left = clippedLeft, top = clippedTop, right = clippedRight, bottom = clippedBottom)
    }

    private fun isMemoryPressure(failure: Throwable): Boolean {
        var cause: Throwable? = failure
        while (cause != null) {
            if (cause is OutOfMemoryError) return true
            val message = cause.message.orEmpty().lowercase()
            if ("out of memory" in message || "failed to allocate" in message) return true
            cause = cause.cause
        }
        return false
    }

    private fun mix(original: Int, generated: Int, amount: Float): Int =
        (original + (generated - original) * amount).roundToInt().coerceIn(0, 255)

    private companion object {
        const val YUNET_MODEL = "yunet"
        const val FACE_MODEL = "face-swinir"
        const val DETECTOR_LONG_SIDE = 320
        const val DETECTION_THRESHOLD = 0.8f
        const val NMS_THRESHOLD = 0.3f
        const val FACE_INPUT_SIZE = 96
        const val FACE_OUTPUT_SIZE = 384
        const val MAX_RESTORED_FACES = 16
        val YUNET_STRIDES = intArrayOf(8, 16, 32)
        val ARCFACE_TEMPLATE_96 = floatArrayOf(
            38.2946f * 96f / 112f, 51.6963f * 96f / 112f,
            73.5318f * 96f / 112f, 51.5014f * 96f / 112f,
            56.0252f * 96f / 112f, 71.7366f * 96f / 112f,
            41.5493f * 96f / 112f, 92.3655f * 96f / 112f,
            70.7299f * 96f / 112f, 92.2041f * 96f / 112f,
        )
    }
}

internal fun decodeYunetHead(
    classification: FloatArray,
    objectness: FloatArray,
    boxes: FloatArray,
    landmarks: FloatArray,
    stride: Int,
    featureWidth: Int,
    featureHeight: Int,
    imageScale: Float,
    threshold: Float,
): List<YunetDetection> {
    val count = featureWidth * featureHeight
    require(
        stride > 0 && featureWidth > 0 && featureHeight > 0 && imageScale > 0f &&
            classification.size == count && objectness.size == count &&
            boxes.size == count * 4 && landmarks.size == count * 10,
    )
    val detections = ArrayList<YunetDetection>()
    for (index in 0 until count) {
        val score = sqrt(classification[index].coerceIn(0f, 1f) * objectness[index].coerceIn(0f, 1f))
        if (!score.isFinite() || score < threshold) continue
        val row = index / featureWidth
        val column = index % featureWidth
        val boxOffset = index * 4
        val centerX = (column + boxes[boxOffset]) * stride / imageScale
        val centerY = (row + boxes[boxOffset + 1]) * stride / imageScale
        val width = exp(boxes[boxOffset + 2].toDouble()).toFloat() * stride / imageScale
        val height = exp(boxes[boxOffset + 3].toDouble()).toFloat() * stride / imageScale
        if (!centerX.isFinite() || !centerY.isFinite() || !width.isFinite() || !height.isFinite() ||
            width <= 0f || height <= 0f
        ) continue
        val points = FloatArray(10)
        val landmarkOffset = index * 10
        var valid = true
        for (point in 0 until 5) {
            points[point * 2] = (column + landmarks[landmarkOffset + point * 2]) * stride / imageScale
            points[point * 2 + 1] = (row + landmarks[landmarkOffset + point * 2 + 1]) * stride / imageScale
            valid = valid && points[point * 2].isFinite() && points[point * 2 + 1].isFinite()
        }
        if (valid) {
            detections += YunetDetection(
                left = centerX - width / 2f,
                top = centerY - height / 2f,
                right = centerX + width / 2f,
                bottom = centerY + height / 2f,
                score = score,
                landmarks = points,
            )
        }
    }
    return detections
}

internal fun nonMaximumSuppression(
    detections: List<YunetDetection>,
    threshold: Float,
): List<YunetDetection> {
    require(threshold in 0f..1f)
    val kept = mutableListOf<YunetDetection>()
    for (candidate in detections.sortedByDescending { it.score }) {
        if (kept.none { intersectionOverUnion(candidate, it) > threshold }) kept += candidate
    }
    return kept
}

private fun intersectionOverUnion(first: YunetDetection, second: YunetDetection): Float {
    val left = max(first.left, second.left)
    val top = max(first.top, second.top)
    val right = min(first.right, second.right)
    val bottom = min(first.bottom, second.bottom)
    val intersection = max(0f, right - left) * max(0f, bottom - top)
    val firstArea = max(0f, first.right - first.left) * max(0f, first.bottom - first.top)
    val secondArea = max(0f, second.right - second.left) * max(0f, second.bottom - second.top)
    val union = firstArea + secondArea - intersection
    return if (union > 0f) intersection / union else 0f
}

internal fun similarityMatrix(source: FloatArray, target: FloatArray): Matrix? {
    if (source.size != 10 || target.size != 10 ||
        source.any { !it.isFinite() } || target.any { !it.isFinite() }
    ) return null
    var sourceX = 0f
    var sourceY = 0f
    var targetX = 0f
    var targetY = 0f
    for (point in 0 until 5) {
        sourceX += source[point * 2]
        sourceY += source[point * 2 + 1]
        targetX += target[point * 2]
        targetY += target[point * 2 + 1]
    }
    sourceX /= 5f
    sourceY /= 5f
    targetX /= 5f
    targetY /= 5f
    var denominator = 0f
    var real = 0f
    var imaginary = 0f
    for (point in 0 until 5) {
        val x = source[point * 2] - sourceX
        val y = source[point * 2 + 1] - sourceY
        val u = target[point * 2] - targetX
        val v = target[point * 2 + 1] - targetY
        denominator += x * x + y * y
        real += x * u + y * v
        imaginary += x * v - y * u
    }
    if (!denominator.isFinite() || denominator <= 1e-8f) return null
    val a = real / denominator
    val b = imaginary / denominator
    val translateX = targetX - a * sourceX + b * sourceY
    val translateY = targetY - b * sourceX - a * sourceY
    if (!a.isFinite() || !b.isFinite() || !translateX.isFinite() || !translateY.isFinite()) return null
    return Matrix().apply {
        setValues(
            floatArrayOf(
                a, -b, translateX,
                b, a, translateY,
                0f, 0f, 1f,
            ),
        )
    }
}

internal fun preservesExistingFaceDetail(alignment: Matrix, tolerance: Float = 0.001f): Boolean {
    require(tolerance >= 0f && tolerance.isFinite())
    val values = FloatArray(9)
    alignment.getValues(values)
    val horizontalScale = sqrt(
        values[Matrix.MSCALE_X] * values[Matrix.MSCALE_X] +
            values[Matrix.MSKEW_Y] * values[Matrix.MSKEW_Y],
    )
    val verticalScale = sqrt(
        values[Matrix.MSKEW_X] * values[Matrix.MSKEW_X] +
            values[Matrix.MSCALE_Y] * values[Matrix.MSCALE_Y],
    )
    if (!horizontalScale.isFinite() || !verticalScale.isFinite()) return false
    val similarityScale = (horizontalScale + verticalScale) * 0.5f
    return similarityScale < 1f - tolerance
}
