package com.bigp.rubiksolver.cube.model

/** Supported physical cube sizes. [n] is the number of stickers along one edge of a face. */
enum class CubeSize(val n: Int, val label: String) {
    TWO(2, "2x2"),
    THREE(3, "3x3"),
    FOUR(4, "4x4"),
    FIVE(5, "5x5");

    /** Facelets on a single face. */
    val faceletsPerFace: Int get() = n * n

    /** Facelets on the whole cube. */
    val totalFacelets: Int get() = 6 * n * n

    /** True for cubes with no fixed centre piece (even layer counts). */
    val isEven: Boolean get() = n % 2 == 0

    companion object {
        fun fromN(n: Int): CubeSize = entries.firstOrNull { it.n == n }
            ?: throw IllegalArgumentException("Unsupported cube size: $n")
    }
}
