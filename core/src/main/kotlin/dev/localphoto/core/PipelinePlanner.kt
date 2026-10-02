package dev.localphoto.core

import kotlin.math.ln
import kotlin.math.max

object PipelinePlanner {
    fun plan(metrics: ImageMetrics, settings: EnhanceSettings): PipelinePlan {
        require(metrics.width > 0 && metrics.height > 0) { "Image dimensions must be positive" }
        val base = settings.adjustments.normalized()
        val detected = detect(metrics)
        val adjustments = if (settings.auto) automaticAdjustments(metrics, base, detected) else base
        val stages = buildList {
            add(Stage.DECODE)
            add(Stage.ANALYZE)
            if (adjustments.denoise > 0f) add(Stage.DENOISE)
            if (adjustments.deblur > 0f) add(Stage.DEBLUR)
            if (hasLightingAdjustment(adjustments)) add(Stage.LIGHTING)
            if (hasColorAdjustment(adjustments)) add(Stage.COLOR)
            if (adjustments.scale > 1) add(Stage.SUPER_RESOLUTION)
            if (adjustments.sharpen > 0f) add(Stage.REFINE)
            add(Stage.EXPORT)
        }
        return PipelinePlan(stages, adjustments, detected)
    }

    private fun detect(metrics: ImageMetrics): Set<Defect> = buildSet {
        val luminance = metrics.luminance.finiteClamped(0f, 1f, 0.5f)
        val contrast = metrics.contrast.finiteClamped(0f, 1f, 0f)
        val noise = metrics.noise.finiteClamped(0f, 1f, 0f)
        val sharpness = metrics.sharpness.finiteClamped(0f, 1f, 0f)
        val saturation = metrics.saturation.finiteClamped(0f, 1f, 0f)
        val colorCast = metrics.colorCast.finiteClamped(0f, 1f, 0f)

        if (metrics.width < 1920 || metrics.height < 1080) add(Defect.LOW_RESOLUTION)
        if (noise > 0.055f) add(Defect.NOISE)
        // Featureless fields have low sharpness but are not evidence of blur.
        if (sharpness < 0.055f && contrast > 0.06f) add(Defect.BLUR)
        if (luminance < 0.28f) add(Defect.UNDEREXPOSURE)
        if (luminance > 0.82f) add(Defect.OVEREXPOSURE)
        if (contrast < 0.08f) add(Defect.LOW_CONTRAST)
        if (colorCast > 0.12f && !metrics.monochrome) add(Defect.COLOR_CAST)
        if (saturation < 0.16f && contrast < 0.24f && !metrics.monochrome) add(Defect.FADED_COLOR)
        if (metrics.monochrome) add(Defect.MONOCHROME)
    }

    private fun automaticAdjustments(
        metrics: ImageMetrics,
        base: Adjustments,
        detected: Set<Defect>,
    ): Adjustments {
        var result = base
        if (Defect.NOISE in detected) {
            val strength = ((metrics.noise - 0.04f) * 420f).coerceIn(12f, 65f)
            result = result.copy(denoise = max(result.denoise, strength))
        }
        if (Defect.BLUR in detected) {
            val strength = ((0.07f - metrics.sharpness) * 600f).coerceIn(10f, 42f)
            result = result.copy(deblur = max(result.deblur, strength))
        }
        if (Defect.UNDEREXPOSURE in detected) {
            val luminance = metrics.luminance.coerceAtLeast(0.01f)
            val exposure = (ln(0.45 / luminance) / ln(2.0)).toFloat().coerceIn(0.25f, 2.5f)
            result = result.copy(
                exposure = max(result.exposure, exposure),
                shadows = max(result.shadows, 20f),
            )
        } else if (Defect.OVEREXPOSURE in detected) {
            val exposure = (ln(0.68 / metrics.luminance.coerceAtLeast(0.01f)) / ln(2.0))
                .toFloat().coerceIn(-1.5f, -0.15f)
            result = result.copy(
                exposure = exposure,
                highlights = result.highlights.coerceAtMost(-18f),
            )
        }
        if (Defect.LOW_CONTRAST in detected) {
            result = result.copy(contrast = max(result.contrast, 18f))
        }
        if (Defect.COLOR_CAST in detected) {
            // Metrics identify cast magnitude, not direction; restrained desaturation is safe.
            result = result.copy(saturation = result.saturation.coerceAtMost(-5f))
        }
        if (Defect.FADED_COLOR in detected) {
            result = result.copy(
                fadedColor = max(result.fadedColor, 25f),
                vibrance = max(result.vibrance, 12f),
            )
        }
        if (metrics.saturation >= 0.9f) {
            result = result.copy(
                saturation = result.saturation.coerceAtMost(0f),
                vibrance = result.vibrance.coerceAtMost(0f),
                fadedColor = 0f,
            )
        }
        // Monochrome detection is reported, but colorization has no bundled implementation.
        return result.normalized()
    }

    private fun hasLightingAdjustment(value: Adjustments): Boolean =
        value.exposure != 0f || value.brightness != 0f || value.contrast != 0f ||
            value.highlights != 0f || value.shadows != 0f || value.whitePoint != 0f ||
            value.blackPoint != 0f || value.gamma != 1f

    private fun hasColorAdjustment(value: Adjustments): Boolean =
        value.temperature != 0f || value.tint != 0f || value.saturation != 0f ||
            value.vibrance != 0f || value.hue != 0f || value.redBalance != 0f ||
            value.greenBalance != 0f || value.blueBalance != 0f || value.fadedColor != 0f
}
