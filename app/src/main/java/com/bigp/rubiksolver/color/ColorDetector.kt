package com.bigp.rubiksolver.color

import com.bigp.rubiksolver.cube.model.FaceletColor

/** One sampled square from a photographed face. */
data class SampledCell(
    val row: Int,
    val col: Int,
    /** Average colour of the sampled patch, as packed sRGB. */
    val averageArgb: Int,
    /** Best-guess sticker colour. */
    val color: FaceletColor,
    /** 0 to 1. Low means the runner-up colour was nearly as close, so this cell is worth a look. */
    val confidence: Float,
    /**
     * Linear luminance of the patch *before* any lighting correction.
     *
     * Kept because the corrected colour deliberately hides how dark the photo was, which is exactly
     * what the low-light warning needs to know. Correcting the exposure makes a dim photo readable;
     * it does not make it a good photo, and a scan shot in the dark is still worth a word to the
     * user.
     */
    val rawLuminance: Float,
)

/** Everything read off one photograph of one face. */
data class FaceReading(
    val size: Int,
    val cells: List<SampledCell>,
) {
    fun colors(): List<FaceletColor> = cells.sortedWith(
        compareBy({ it.row }, { it.col })
    ).map { it.color }

    /** The cells least likely to be right, worst first. */
    fun leastConfident(count: Int): List<SampledCell> =
        cells.sortedBy { it.confidence }.take(count)

    val lowestConfidence: Float get() = cells.minOfOrNull { it.confidence } ?: 0f

    /** Median linear luminance across the face, before correction. */
    val medianRawLuminance: Float
        get() {
            if (cells.isEmpty()) return 0f
            val sorted = cells.map { it.rawLuminance }.sorted()
            return sorted[sorted.size / 2]
        }
}

/**
 * Reads sticker colours off a photographed cube face.
 *
 * Deliberately narrow: it takes pixels and gives back colours, and knows nothing about cameras,
 * files or the rest of the app. Version one reads them on the device, but a cloud-backed reader
 * would implement this same interface and nothing above it would have to change.
 */
interface ColorDetector {
    /**
     * @param pixels row-major ARGB pixels of a square crop already aligned to the face.
     * @param width  side length of that crop, in pixels.
     * @param n      stickers along one edge of the face.
     */
    fun read(pixels: IntArray, width: Int, n: Int): FaceReading
}
