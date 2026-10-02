package dev.localphoto.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DomainEngineTest {
    @Test
    fun adjustmentsNormalizeEveryPublicRangeAndNonFiniteValue() {
        val normalized = Adjustments(
            scale = 7,
            denoise = Float.NaN,
            deblur = -5f,
            sharpen = 150f,
            detailPreservation = Float.POSITIVE_INFINITY,
            exposure = 9f,
            brightness = -140f,
            gamma = 0.01f,
            hue = 500f,
            saturation = 140f,
            fadedColor = -1f,
        ).normalized()

        assertEquals(4, normalized.scale)
        assertEquals(0f, normalized.denoise)
        assertEquals(0f, normalized.deblur)
        assertEquals(100f, normalized.sharpen)
        assertEquals(80f, normalized.detailPreservation)
        assertEquals(3f, normalized.exposure)
        assertEquals(-100f, normalized.brightness)
        assertEquals(0.2f, normalized.gamma)
        assertEquals(180f, normalized.hue)
        assertEquals(100f, normalized.saturation)
        assertEquals(0f, normalized.fadedColor)
    }

    @Test
    fun transformNormalizationKeepsCropOrderedAndInsideImage() {
        val normalized = TransformSettings(
            rotationDegrees = -90,
            straightenDegrees = 80f,
            cropLeft = 1.2f,
            cropTop = 0.8f,
            cropRight = -0.3f,
            cropBottom = 0.2f,
        ).normalized()

        assertEquals(270, normalized.rotationDegrees)
        assertEquals(45f, normalized.straightenDegrees)
        assertEquals(0f, normalized.cropLeft)
        assertEquals(0.2f, normalized.cropTop)
        assertEquals(1f, normalized.cropRight)
        assertEquals(0.8f, normalized.cropBottom)
    }

    @Test
    fun memoryPolicyChoosesHighestSafeScaleAndRejectsOverflow() {
        val limited = MemoryPolicy.assess(
            width = 4_000,
            height = 3_000,
            requestedScale = 8,
            memoryBudgetBytes = 1_000_000_000L,
        )

        assertEquals(2, limited.safeScale)
        assertEquals(8_000, limited.outputWidth)
        assertEquals(6_000, limited.outputHeight)
        assertTrue(limited.isSafe)

        val impossible = MemoryPolicy.assess(
            width = 4_000,
            height = 3_000,
            requestedScale = 8,
            memoryBudgetBytes = 32L * 1024L * 1024L,
        )
        assertEquals(1, impossible.safeScale)
        assertFalse(impossible.isSafe)

        val overflow = MemoryPolicy.assess(
            width = Int.MAX_VALUE,
            height = Int.MAX_VALUE,
            requestedScale = 8,
            memoryBudgetBytes = Long.MAX_VALUE,
        )
        assertEquals(1, overflow.safeScale)
        assertEquals(Long.MAX_VALUE, overflow.estimatedBytes)
        assertFalse(overflow.isSafe)
    }

    @Test
    fun cleanConstantImageDoesNotTriggerNoiseOrDeblur() {
        val pixels = IntArray(64 * 64) { 0xff808080.toInt() }
        val metrics = ImageAnalyzer.analyze(pixels, 64, 64)
        val plan = PipelinePlanner.plan(
            metrics,
            EnhanceSettings(adjustments = Adjustments(scale = 1, sharpen = 0f)),
        )

        assertEquals(0f, metrics.noise, 0.0001f)
        assertFalse(Defect.NOISE in plan.detected)
        assertFalse(Defect.BLUR in plan.detected)
        assertFalse(Stage.DENOISE in plan.stages)
        assertFalse(Stage.DEBLUR in plan.stages)
    }

    @Test
    fun autoPlanLiftsDarkPhotoWithoutChangingRequestedResolution() {
        val metrics = ImageMetrics(
            width = 2400,
            height = 1600,
            luminance = 0.08f,
            contrast = 0.18f,
            noise = 0.02f,
            sharpness = 0.22f,
            saturation = 0.35f,
            colorCast = 0.02f,
            monochrome = false,
        )
        val plan = PipelinePlanner.plan(
            metrics,
            EnhanceSettings(adjustments = Adjustments(scale = 4, sharpen = 0f)),
        )

        assertTrue(Defect.UNDEREXPOSURE in plan.detected)
        assertTrue(Stage.LIGHTING in plan.stages)
        assertTrue(plan.adjustments.exposure > 0f)
        assertEquals(4, plan.adjustments.scale)
        assertTrue(Stage.SUPER_RESOLUTION in plan.stages)

        val darkPixels = IntArray(16) { gray(20) }
        val corrected = PixelProcessor.process(darkPixels, 4, 4, plan.adjustments)
        assertTrue("lighting plan must make dark pixels visible", red(corrected[0]) > red(darkPixels[0]))
        assertTrue("lighting correction must retain headroom", red(corrected[0]) < 255)
    }

    @Test
    fun saturatedImageDoesNotReceiveAutomaticSaturationBoost() {
        val plan = PipelinePlanner.plan(
            ImageMetrics(
                width = 2400,
                height = 1600,
                luminance = 0.5f,
                contrast = 0.2f,
                noise = 0.01f,
                sharpness = 0.2f,
                saturation = 0.96f,
                colorCast = 0.01f,
                monochrome = false,
            ),
            EnhanceSettings(adjustments = Adjustments(scale = 1, sharpen = 0f)),
        )

        assertFalse(Defect.FADED_COLOR in plan.detected)
        assertTrue(plan.adjustments.saturation <= 0f)
        assertTrue(plan.adjustments.vibrance <= 0f)
        assertFalse(Stage.COLOR in plan.stages)
    }

    @Test
    fun edgeAwareDenoiseReducesNoiseWhileKeepingStrongBoundary() {
        val width = 32
        val height = 16
        val input = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val base = if (x < width / 2) 60 else 200
            val noise = if ((x + y) % 2 == 0) 22 else -22
            gray(base + noise)
        }
        val output = PixelProcessor.process(
            input,
            width,
            height,
            Adjustments(denoise = 85f, detailPreservation = 80f, sharpen = 0f),
        )

        val beforeVariation = meanNeighborVariation(input, width, 1, width / 2 - 1)
        val afterVariation = meanNeighborVariation(output, width, 1, width / 2 - 1)
        assertTrue("denoise must lower same-region variation", afterVariation < beforeVariation * 0.65)

        val beforeEdge = averageColumn(input, width, width / 2) - averageColumn(input, width, width / 2 - 1)
        val afterEdge = averageColumn(output, width, width / 2) - averageColumn(output, width, width / 2 - 1)
        assertTrue("edge contrast must remain recognizable", afterEdge > beforeEdge * 0.75)
        assertTrue(output.indices.all { (output[it] ushr 24) == (input[it] ushr 24) })
    }

    @Test
    fun saturatedPixelsRemainBoundedAndAlphaIsPreserved() {
        val pixels = intArrayOf(
            0x40ff0000,
            0x8000ff00.toInt(),
            0xc00000ff.toInt(),
            0xffffffff.toInt(),
        )
        val output = PixelProcessor.process(
            pixels,
            width = 4,
            height = 1,
            adjustments = Adjustments(
                sharpen = 100f,
                deblur = 100f,
                saturation = 100f,
                vibrance = 100f,
            ),
        )

        output.indices.forEach { index ->
            assertEquals(pixels[index] ushr 24, output[index] ushr 24)
            assertTrue(red(output[index]) in 0..255)
            assertTrue(green(output[index]) in 0..255)
            assertTrue(blue(output[index]) in 0..255)
        }
        assertTrue(red(output[0]) >= green(output[0]) && red(output[0]) >= blue(output[0]))
        assertTrue(green(output[1]) >= red(output[1]) && green(output[1]) >= blue(output[1]))
        assertTrue(blue(output[2]) >= red(output[2]) && blue(output[2]) >= green(output[2]))
        assertEquals(0xffffffff.toInt(), output[3])
    }

    @Test
    fun tileCoresCoverEveryPixelExactlyOnce() {
        val width = 37
        val height = 29
        val tiles = TilePlanner.tiles(width, height, tileSize = 16, overlap = 3)
        val coverage = IntArray(width * height)

        tiles.forEach { tile ->
            assertTrue(tile.left >= 0 && tile.top >= 0)
            assertTrue(tile.left + tile.width <= width)
            assertTrue(tile.top + tile.height <= height)
            assertTrue(tile.coreLeft >= tile.left && tile.coreTop >= tile.top)
            assertTrue(tile.coreLeft + tile.coreWidth <= tile.left + tile.width)
            assertTrue(tile.coreTop + tile.coreHeight <= tile.top + tile.height)
            assertTrue(tile.width <= 16 && tile.height <= 16)
            for (y in tile.coreTop until tile.coreTop + tile.coreHeight) {
                for (x in tile.coreLeft until tile.coreLeft + tile.coreWidth) {
                    coverage[y * width + x]++
                }
            }
        }

        assertTrue(coverage.all { it == 1 })
    }

    @Test
    fun queueContinuesPastFailuresButCompletedItemCannotBeCancelled() {
        val statuses = listOf(
            ProjectStatus.COMPLETED,
            ProjectStatus.FAILED,
            ProjectStatus.CANCELLED,
            ProjectStatus.QUEUED,
            ProjectStatus.QUEUED,
        )

        assertEquals(3, QueueRules.nextRunnable(statuses))
        assertTrue(QueueRules.canTransition(ProjectStatus.FAILED, ProjectStatus.QUEUED))
        assertFalse(QueueRules.canTransition(ProjectStatus.COMPLETED, ProjectStatus.CANCELLED))
        assertFalse(QueueRules.canTransition(ProjectStatus.COMPLETED, ProjectStatus.PROCESSING))
    }

    private fun gray(value: Int): Int {
        val channel = value.coerceIn(0, 255)
        return (0xff shl 24) or (channel shl 16) or (channel shl 8) or channel
    }

    private fun red(pixel: Int): Int = pixel ushr 16 and 0xff
    private fun green(pixel: Int): Int = pixel ushr 8 and 0xff
    private fun blue(pixel: Int): Int = pixel and 0xff

    private fun meanNeighborVariation(pixels: IntArray, width: Int, startX: Int, endX: Int): Double {
        var total = 0L
        var count = 0
        val height = pixels.size / width
        for (y in 0 until height) {
            for (x in startX until endX) {
                total += abs(red(pixels[y * width + x + 1]) - red(pixels[y * width + x]))
                count++
            }
        }
        return total.toDouble() / count
    }

    private fun averageColumn(pixels: IntArray, width: Int, x: Int): Double {
        var total = 0L
        val height = pixels.size / width
        for (y in 0 until height) total += red(pixels[y * width + x])
        return total.toDouble() / height
    }
}
