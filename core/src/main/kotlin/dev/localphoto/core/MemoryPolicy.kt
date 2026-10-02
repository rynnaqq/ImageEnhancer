package dev.localphoto.core

object MemoryPolicy {
    private const val BYTES_PER_INPUT_PIXEL = 4L
    private const val BYTES_PER_OUTPUT_PIXEL = 12L
    const val WORKING_RESERVE_BYTES = 32L * 1024L * 1024L

    fun assess(
        width: Int,
        height: Int,
        requestedScale: Int,
        memoryBudgetBytes: Long,
    ): MemoryAssessment {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        val maximumScale = normalizeScale(requestedScale)
        val candidates = intArrayOf(8, 4, 2, 1).filter { it <= maximumScale }
        val evaluated = candidates.map { candidate(width, height, it, memoryBudgetBytes) }
        val selected = evaluated.firstOrNull { it.isSafe } ?: evaluated.last()
        return MemoryAssessment(
            safeScale = selected.scale,
            outputWidth = selected.outputWidth,
            outputHeight = selected.outputHeight,
            estimatedBytes = selected.estimatedBytes,
            isSafe = selected.isSafe,
        )
    }

    private fun candidate(width: Int, height: Int, scale: Int, budget: Long): Candidate {
        var overflow = false
        val outputWidthLong = width.toLong() * scale.toLong()
        val outputHeightLong = height.toLong() * scale.toLong()
        if (outputWidthLong > Int.MAX_VALUE || outputHeightLong > Int.MAX_VALUE) overflow = true

        val inputPixels = saturatedMultiply(width.toLong(), height.toLong()).also {
            if (it.overflow) overflow = true
        }
        val outputPixels = saturatedMultiply(outputWidthLong, outputHeightLong).also {
            if (it.overflow) overflow = true
        }
        val inputBytes = saturatedMultiply(inputPixels.value, BYTES_PER_INPUT_PIXEL).also {
            if (it.overflow) overflow = true
        }
        val outputBytes = saturatedMultiply(outputPixels.value, BYTES_PER_OUTPUT_PIXEL).also {
            if (it.overflow) overflow = true
        }
        val imageBytes = saturatedAdd(inputBytes.value, outputBytes.value).also {
            if (it.overflow) overflow = true
        }
        val total = saturatedAdd(imageBytes.value, WORKING_RESERVE_BYTES).also {
            if (it.overflow) overflow = true
        }
        val safe = !overflow && budget >= 0L && total.value <= budget
        return Candidate(
            scale = scale,
            outputWidth = outputWidthLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            outputHeight = outputHeightLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            estimatedBytes = total.value,
            isSafe = safe,
        )
    }

    private fun saturatedMultiply(left: Long, right: Long): ArithmeticResult {
        if (left == 0L || right == 0L) return ArithmeticResult(0L, false)
        if (left > Long.MAX_VALUE / right) return ArithmeticResult(Long.MAX_VALUE, true)
        return ArithmeticResult(left * right, false)
    }

    private fun saturatedAdd(left: Long, right: Long): ArithmeticResult {
        if (left > Long.MAX_VALUE - right) return ArithmeticResult(Long.MAX_VALUE, true)
        return ArithmeticResult(left + right, false)
    }

    private data class ArithmeticResult(val value: Long, val overflow: Boolean)

    private data class Candidate(
        val scale: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        val estimatedBytes: Long,
        val isSafe: Boolean,
    )
}
