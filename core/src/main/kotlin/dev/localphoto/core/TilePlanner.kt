package dev.localphoto.core

import kotlin.math.max
import kotlin.math.min

object TilePlanner {
    fun tiles(width: Int, height: Int, tileSize: Int, overlap: Int): List<Tile> {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        require(overlap >= 0) { "Overlap cannot be negative" }
        require(tileSize > 0 && overlap <= (tileSize - 1) / 2) {
            "Tile size must exceed two overlap regions"
        }
        val stride = tileSize - overlap * 2
        val result = ArrayList<Tile>()
        var coreTop = 0
        while (coreTop < height) {
            val coreHeight = min(stride, height - coreTop)
            var coreLeft = 0
            while (coreLeft < width) {
                val coreWidth = min(stride, width - coreLeft)
                val tileLeft = max(0, coreLeft - overlap)
                val tileTop = max(0, coreTop - overlap)
                val tileRight = min(width, coreLeft + coreWidth + overlap)
                val tileBottom = min(height, coreTop + coreHeight + overlap)
                result += Tile(
                    left = tileLeft,
                    top = tileTop,
                    width = tileRight - tileLeft,
                    height = tileBottom - tileTop,
                    coreLeft = coreLeft,
                    coreTop = coreTop,
                    coreWidth = coreWidth,
                    coreHeight = coreHeight,
                )
                coreLeft += coreWidth
            }
            coreTop += coreHeight
        }
        return result
    }
}
