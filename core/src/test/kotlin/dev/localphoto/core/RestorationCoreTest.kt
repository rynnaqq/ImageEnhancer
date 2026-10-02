package dev.localphoto.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorationCoreTest {
    @Test
    fun restorationSettingsDropInvalidPointsAndBoundStoredBrushData() {
        val oversizedStroke = RepairStroke(
            points = List(2_050) { index ->
                if (index == 0) RepairPoint(Float.NaN, 0.5f)
                else RepairPoint(index.toFloat(), -index.toFloat())
            },
            radius = Float.POSITIVE_INFINITY,
        )
        val normalized = RestorationSettings(
            faceStrength = 120f,
            colorStrength = Float.NaN,
            repairStrength = -5f,
            maskStrokes = List(6) { oversizedStroke },
        ).normalized()
        val strokeBounded = RestorationSettings(
            maskStrokes = List(300) { RepairStroke(listOf(RepairPoint(0.25f, 0.75f))) },
        ).normalized()

        assertEquals(100f, normalized.faceStrength)
        assertEquals(70f, normalized.colorStrength)
        assertEquals(0f, normalized.repairStrength)
        assertEquals(4, normalized.maskStrokes.size)
        assertEquals(2_048, normalized.maskStrokes.first().points.size)
        assertEquals(8_192, normalized.maskStrokes.sumOf { it.points.size })
        assertEquals(256, strokeBounded.maskStrokes.size)
        assertTrue(normalized.maskStrokes.flatMap { it.points }.all { it.x in 0f..1f && it.y in 0f..1f })
        assertTrue(normalized.maskStrokes.flatMap { it.points }.all { it.x.isFinite() && it.y.isFinite() })
        assertEquals(RepairPoint(1f, 0f), normalized.maskStrokes.first().points.first())
        assertEquals(0.025f, normalized.maskStrokes.first().radius)
    }

    @Test
    fun plannerAddsOnlyOptedInRestorationStagesInProcessingOrder() {
        val plan = PipelinePlanner.plan(
            neutralMetrics,
            EnhanceSettings(
                auto = false,
                adjustments = Adjustments(scale = 2, sharpen = 10f, saturation = 5f),
                restoration = RestorationSettings(
                    faceStrength = 60f,
                    colorize = true,
                    colorStrength = 75f,
                    maskStrokes = listOf(
                        RepairStroke(listOf(RepairPoint(0.2f, 0.2f), RepairPoint(0.8f, 0.8f))),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                Stage.DECODE,
                Stage.ANALYZE,
                Stage.COLOR,
                Stage.REPAIR,
                Stage.COLORIZE,
                Stage.FACE_RESTORE,
                Stage.SUPER_RESOLUTION,
                Stage.REFINE,
                Stage.EXPORT,
            ),
            plan.stages,
        )
    }

    @Test
    fun defaultRestorationSettingsKeepExistingPlanUnchanged() {
        val plan = PipelinePlanner.plan(
            neutralMetrics,
            EnhanceSettings(
                auto = false,
                adjustments = Adjustments(scale = 1, sharpen = 0f),
            ),
        )

        assertEquals(listOf(Stage.DECODE, Stage.ANALYZE, Stage.EXPORT), plan.stages)
    }

    @Test
    fun brushRasterizationConnectsPointsAndAppliesEraseStrokesInOrder() {
        val draw = RepairStroke(
            points = listOf(RepairPoint(0.1f, 0.5f), RepairPoint(0.9f, 0.5f)),
            radius = 0.06f,
        )
        val erase = RepairStroke(
            points = listOf(RepairPoint(0.5f, 0.5f)),
            radius = 0.15f,
            erase = true,
        )
        val mask = RestorationPixels.rasterMask(11, 11, listOf(draw, erase))

        assertTrue(mask[5 * 11 + 2])
        assertFalse(mask[5 * 11 + 5])
        assertTrue(mask[5 * 11 + 8])
        assertFalse(mask[0])
    }

    @Test
    fun rgbLabConversionMatchesHandCheckedReferenceColorsAndAlpha() {
        val white = RestorationPixels.rgbToLab(0xffffffff.toInt())
        val black = RestorationPixels.rgbToLab(0xff000000.toInt())
        val red = RestorationPixels.rgbToLab(0xffff0000.toInt())

        assertEquals(100f, white[0], 0.01f)
        assertEquals(0f, white[1], 0.03f)
        assertEquals(0f, white[2], 0.03f)
        assertEquals(0f, black[0], 0.01f)
        assertEquals(53.24f, red[0], 0.03f)
        assertEquals(80.09f, red[1], 0.05f)
        assertEquals(67.20f, red[2], 0.05f)

        assertEquals(
            0x40ff0000,
            RestorationPixels.labToArgb(red[0], red[1], red[2], alpha = 0x40),
        )
    }

    @Test
    fun maskedBlendKeepsUnselectedPixelsExactAndAlwaysPreservesOriginalAlpha() {
        val original = intArrayOf(0x10203040, 0x806080a0.toInt(), 0xff010203.toInt())
        val generated = intArrayOf(0xffffffff.toInt(), 0x00a0c0e0, 0xffff0000.toInt())
        val output = RestorationPixels.blendMasked(
            original,
            generated,
            booleanArrayOf(false, true, true),
            strength = 50f,
        )

        assertEquals(original[0], output[0])
        assertEquals(0x8080a0c0.toInt(), output[1])
        assertEquals(0xff800102.toInt(), output[2])
        assertEquals(original[1] ushr 24, output[1] ushr 24)
        assertEquals(original[2] ushr 24, output[2] ushr 24)
    }

    @Test
    fun scratchDetectionFindsThinLongContrastLineWithoutSelectingBroadRegions() {
        val width = 32
        val height = 32
        val clean = IntArray(width * height) { 0xffc8c8c8.toInt() }
        assertArrayEquals(BooleanArray(clean.size), RestorationPixels.detectScratches(clean, width, height))

        val scratched = clean.copyOf()
        for (y in 6..25) scratched[y * width + 16] = 0xff202020.toInt()
        val detected = RestorationPixels.detectScratches(scratched, width, height)
        assertTrue((6..25).count { detected[it * width + 16] } >= 16)
        assertTrue(detected.count { it } < scratched.size / 10)

        val brightScratch = clean.copyOf()
        for (x in 6..25) brightScratch[16 * width + x] = 0xffffffff.toInt()
        val brightDetected = RestorationPixels.detectScratches(brightScratch, width, height)
        assertTrue((6..25).count { brightDetected[16 * width + it] } >= 16)

        val broad = clean.copyOf()
        for (y in 8..23) for (x in 8..23) broad[y * width + x] = 0xff202020.toInt()
        assertTrue(RestorationPixels.detectScratches(broad, width, height).none { it })
    }

    private val neutralMetrics = ImageMetrics(
        width = 2400,
        height = 1600,
        luminance = 0.5f,
        contrast = 0.2f,
        noise = 0.01f,
        sharpness = 0.2f,
        saturation = 0.4f,
        colorCast = 0.01f,
        monochrome = false,
    )
}
