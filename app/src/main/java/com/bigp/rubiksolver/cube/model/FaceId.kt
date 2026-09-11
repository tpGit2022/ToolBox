package com.bigp.rubiksolver.cube.model

/**
 * The six faces of the cube in standard singmaster order.
 *
 * The whole app stores cube state by face *letter*, never by colour, so a user whose physical
 * cube uses a non-standard colour scheme still works. The colour on each face is discovered at
 * scan time.
 *
 * Geometry convention (used by [com.bigp.rubiksolver.cube.model.MoveEngine]):
 * with x to the right, y up and z toward the viewer, and the cube occupying [0, n-1] on each
 * axis, the faces sit at U:y=n-1, D:y=0, R:x=n-1, L:x=0, F:z=n-1, B:z=0.
 */
enum class FaceId(val letter: Char) {
    U('U'), R('R'), F('F'), D('D'), L('L'), B('B');

    val opposite: FaceId
        get() = when (this) {
            U -> D; D -> U
            R -> L; L -> R
            F -> B; B -> F
        }

    companion object {
        /** Canonical capture / storage order. */
        val ORDER: List<FaceId> = listOf(U, R, F, D, L, B)

        fun fromLetter(c: Char): FaceId? = entries.firstOrNull { it.letter == c.uppercaseChar() }
    }
}
