package dev.localphoto.core

import kotlin.math.max
import kotlin.math.min

enum class Profile { QUALITY, BALANCED, FAST }

enum class OutputFormat { JPEG, PNG, WEBP }

enum class Stage {
    DECODE,
    ANALYZE,
    DENOISE,
    DEBLUR,
    LIGHTING,
    COLOR,
    SUPER_RESOLUTION,
    REFINE,
    EXPORT,
}

enum class ProjectStatus { DRAFT, QUEUED, PROCESSING, COMPLETED, CANCELLED, FAILED, INTERRUPTED }

enum class Defect {
    LOW_RESOLUTION,
    NOISE,
    BLUR,
    UNDEREXPOSURE,
    OVEREXPOSURE,
    LOW_CONTRAST,
    COLOR_CAST,
    FADED_COLOR,
    MONOCHROME,
}

data class Adjustments(
    val scale: Int = 2,
    val denoise: Float = 0f,
    val deblur: Float = 0f,
    val sharpen: Float = 15f,
    val detailPreservation: Float = 80f,
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whitePoint: Float = 0f,
    val blackPoint: Float = 0f,
    val gamma: Float = 1f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val hue: Float = 0f,
    val redBalance: Float = 0f,
    val greenBalance: Float = 0f,
    val blueBalance: Float = 0f,
    val fadedColor: Float = 0f,
) {
    fun normalized(): Adjustments = copy(
        scale = normalizeScale(scale),
        denoise = denoise.finiteClamped(0f, 100f, 0f),
        deblur = deblur.finiteClamped(0f, 100f, 0f),
        sharpen = sharpen.finiteClamped(0f, 100f, 15f),
        detailPreservation = detailPreservation.finiteClamped(0f, 100f, 80f),
        exposure = exposure.finiteClamped(-3f, 3f, 0f),
        brightness = brightness.finiteClamped(-100f, 100f, 0f),
        contrast = contrast.finiteClamped(-100f, 100f, 0f),
        highlights = highlights.finiteClamped(-100f, 100f, 0f),
        shadows = shadows.finiteClamped(-100f, 100f, 0f),
        whitePoint = whitePoint.finiteClamped(-100f, 100f, 0f),
        blackPoint = blackPoint.finiteClamped(-100f, 100f, 0f),
        gamma = gamma.finiteClamped(0.2f, 3f, 1f),
        temperature = temperature.finiteClamped(-100f, 100f, 0f),
        tint = tint.finiteClamped(-100f, 100f, 0f),
        saturation = saturation.finiteClamped(-100f, 100f, 0f),
        vibrance = vibrance.finiteClamped(-100f, 100f, 0f),
        hue = hue.finiteClamped(-180f, 180f, 0f),
        redBalance = redBalance.finiteClamped(-100f, 100f, 0f),
        greenBalance = greenBalance.finiteClamped(-100f, 100f, 0f),
        blueBalance = blueBalance.finiteClamped(-100f, 100f, 0f),
        fadedColor = fadedColor.finiteClamped(0f, 100f, 0f),
    )
}

data class TransformSettings(
    val rotationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val straightenDegrees: Float = 0f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
) {
    fun normalized(): TransformSettings {
        val left = cropLeft.finiteClamped(0f, 1f, 0f)
        val top = cropTop.finiteClamped(0f, 1f, 0f)
        val right = cropRight.finiteClamped(0f, 1f, 1f)
        val bottom = cropBottom.finiteClamped(0f, 1f, 1f)
        val orderedLeft = min(left, right)
        val orderedTop = min(top, bottom)
        val orderedRight = max(left, right)
        val orderedBottom = max(top, bottom)
        return copy(
            rotationDegrees = ((rotationDegrees % 360) + 360) % 360,
            straightenDegrees = straightenDegrees.finiteClamped(-45f, 45f, 0f),
            cropLeft = if (orderedLeft == orderedRight) 0f else orderedLeft,
            cropTop = if (orderedTop == orderedBottom) 0f else orderedTop,
            cropRight = if (orderedLeft == orderedRight) 1f else orderedRight,
            cropBottom = if (orderedTop == orderedBottom) 1f else orderedBottom,
        )
    }
}

data class OutputSettings(
    val format: OutputFormat = OutputFormat.JPEG,
    val quality: Int = 97,
    val stripMetadata: Boolean = true,
) {
    fun normalized(): OutputSettings = copy(quality = quality.coerceIn(1, 100))
}

data class EnhanceSettings(
    val auto: Boolean = true,
    val profile: Profile = Profile.QUALITY,
    val adjustments: Adjustments = Adjustments(),
    val transform: TransformSettings = TransformSettings(),
    val output: OutputSettings = OutputSettings(),
)

data class ImageMetrics(
    val width: Int,
    val height: Int,
    val luminance: Float,
    val contrast: Float,
    val noise: Float,
    val sharpness: Float,
    val saturation: Float,
    val colorCast: Float,
    val monochrome: Boolean,
)

data class PipelinePlan(
    val stages: List<Stage>,
    val adjustments: Adjustments,
    val detected: Set<Defect>,
)

data class MemoryAssessment(
    val safeScale: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val estimatedBytes: Long,
    val isSafe: Boolean,
)

data class Tile(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val coreLeft: Int,
    val coreTop: Int,
    val coreWidth: Int,
    val coreHeight: Int,
)

internal fun normalizeScale(scale: Int): Int = when {
    scale >= 8 -> 8
    scale >= 4 -> 4
    scale >= 2 -> 2
    else -> 1
}

internal fun Float.finiteClamped(minimum: Float, maximum: Float, fallback: Float): Float =
    if (isFinite()) coerceIn(minimum, maximum) else fallback
