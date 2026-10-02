package dev.localphoto.enhancer

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.localphoto.enhancer.ai.YunetDetection
import dev.localphoto.enhancer.ai.decodeYunetHead
import dev.localphoto.enhancer.ai.nonMaximumSuppression
import dev.localphoto.enhancer.ai.preservesExistingFaceDetail
import dev.localphoto.enhancer.ai.similarityMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class FaceModelInstrumentedTest {
    @Test
    fun yunetDecoderUsesGridOffsetsExponentialSizeAndGeometricScore() {
        val detections = decodeYunetHead(
            classification = floatArrayOf(0.81f),
            objectness = floatArrayOf(1f),
            boxes = floatArrayOf(0.5f, 0.5f, ln(2f), ln(4f)),
            landmarks = FloatArray(10) { 0.25f },
            stride = 8,
            featureWidth = 1,
            featureHeight = 1,
            imageScale = 2f,
            threshold = 0.8f,
        )

        assertEquals(1, detections.size)
        val face = detections.single()
        assertEquals(0.9f, face.score, 0.0001f)
        assertEquals(-2f, face.left, 0.0001f)
        assertEquals(-6f, face.top, 0.0001f)
        assertEquals(6f, face.right, 0.0001f)
        assertEquals(10f, face.bottom, 0.0001f)
        assertEquals(1f, face.landmarks[0], 0.0001f)
        assertEquals(1f, face.landmarks[1], 0.0001f)
    }

    @Test
    fun nmsKeepsHighestScoreForOverlappingFaces() {
        val first = detection(0f, 0f, 20f, 20f, 0.95f)
        val overlap = detection(1f, 1f, 21f, 21f, 0.85f)
        val separate = detection(30f, 30f, 40f, 40f, 0.82f)

        assertEquals(listOf(first, separate), nonMaximumSuppression(listOf(overlap, separate, first), 0.3f))
    }

    @Test
    fun similarityFitMapsArcFaceTemplateFrom112To96() {
        val source = floatArrayOf(
            38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f,
            71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f,
        )
        val target = FloatArray(source.size) { source[it] * 96f / 112f }
        val values = FloatArray(9)
        similarityMatrix(source, target)!!.getValues(values)

        assertEquals(96f / 112f, values[0], 0.0001f)
        assertEquals(0f, values[1], 0.0001f)
        assertEquals(0f, values[2], 0.001f)
        assertEquals(0f, values[3], 0.0001f)
        assertEquals(96f / 112f, values[4], 0.0001f)
        assertTrue(values.all { it.isFinite() })
    }

    @Test
    fun detailGuardUsesRotationInvariantSimilarityMagnitude() {
        assertTrue(preservesExistingFaceDetail(similarity(scale = 0.8f, radians = 0.7f)))
        assertFalse(preservesExistingFaceDetail(similarity(scale = 1f, radians = 0.7f)))
        assertFalse(preservesExistingFaceDetail(similarity(scale = 1.2f, radians = 0.7f)))
        assertFalse("rounding near unit scale must not suppress restoration",
            preservesExistingFaceDetail(similarity(scale = 0.9995f, radians = 0.7f)))
    }

    private fun detection(left: Float, top: Float, right: Float, bottom: Float, score: Float) =
        YunetDetection(left, top, right, bottom, score, FloatArray(10))

    private fun similarity(scale: Float, radians: Float) = android.graphics.Matrix().apply {
        val a = cos(radians) * scale
        val b = sin(radians) * scale
        setValues(floatArrayOf(a, -b, 12f, b, a, -7f, 0f, 0f, 1f))
    }
}
