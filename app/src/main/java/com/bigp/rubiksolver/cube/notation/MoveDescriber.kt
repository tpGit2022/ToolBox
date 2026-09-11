package com.bigp.rubiksolver.cube.notation

import com.bigp.rubiksolver.cube.model.CubeSize
import com.bigp.rubiksolver.cube.model.Direction
import com.bigp.rubiksolver.cube.model.FaceId
import com.bigp.rubiksolver.cube.model.FaceletColor
import com.bigp.rubiksolver.cube.model.Move

/**
 * Turns a [Move] into a sentence someone who has never seen cube notation can follow.
 *
 * Everything is phrased against a fixed holding position: the user keeps the same face toward them
 * for the whole solve, exactly as during capture. When the scanned colour scheme is known the
 * sentence names colours too, since "the green face" beats "the front face" when you are holding
 * a cube and second-guessing yourself.
 */
object MoveDescriber {

    private fun faceNoun(face: FaceId): String = when (face) {
        FaceId.U -> "top"
        FaceId.D -> "bottom"
        FaceId.R -> "right"
        FaceId.L -> "left"
        FaceId.F -> "front"
        FaceId.B -> "back"
    }

    /** Which way a turn looks when you are staring straight at that face. */
    private fun turnPhrase(direction: Direction): String = when (direction) {
        Direction.CW -> "clockwise"
        Direction.CCW -> "anticlockwise"
        Direction.DOUBLE -> "half a turn (180 degrees)"
    }

    /**
     * A hint about what that rotation looks like from the user's fixed viewpoint, which is the part
     * people get wrong. Only added for faces that are not pointing at the user.
     */
    private fun viewpointHint(face: FaceId, direction: Direction): String? {
        if (direction == Direction.DOUBLE) return null
        val cw = direction == Direction.CW
        return when (face) {
            FaceId.F -> null
            FaceId.B -> "you are looking at it through the cube, so from where you sit it turns " +
                (if (cw) "anticlockwise" else "clockwise")
            FaceId.U -> "the front of that layer moves to the " + (if (cw) "left" else "right")
            FaceId.D -> "the front of that layer moves to the " + (if (cw) "right" else "left")
            FaceId.R -> "the front of that layer moves " + (if (cw) "up" else "down")
            FaceId.L -> "the front of that layer moves " + (if (cw) "down" else "up")
        }
    }

    private fun ordinal(k: Int): String = when (k) {
        1 -> "first"
        2 -> "second"
        3 -> "third"
        4 -> "fourth"
        5 -> "fifth"
        else -> k.toString() + "th"
    }

    /** Names the layers a move grabs, e.g. "the right face" or "the two top layers". */
    private fun layerPhrase(move: Move, size: CubeSize, colorOf: Map<FaceId, FaceletColor>?): String {
        val noun = faceNoun(move.face)
        val colour = colorOf?.get(move.face)?.displayName?.lowercase()
        val named = if (colour != null) "$noun ($colour) " else "$noun "
        return when {
            !move.wide && move.layer == 1 ->
                "the ${named}face"
            move.wide ->
                "the ${move.layer} layers starting from the $named side"
            else ->
                "the ${ordinal(move.layer)} layer in from the $named side"
        }
    }

    /** A full sentence for one move. */
    fun describe(
        move: Move,
        size: CubeSize,
        colorOf: Map<FaceId, FaceletColor>? = null,
    ): String {
        val layers = layerPhrase(move, size, colorOf)
        val turn = turnPhrase(move.direction)
        val hint = viewpointHint(move.face, move.direction)
        val base = "Turn $layers $turn."
        return if (hint == null) base else "$base Looking at the ${faceNoun(move.face)}, $hint."
    }

    /** A short label for tight spaces, e.g. "Right face, clockwise". */
    fun shortLabel(move: Move, colorOf: Map<FaceId, FaceletColor>? = null): String {
        val colour = colorOf?.get(move.face)?.displayName
        val face = faceNoun(move.face).replaceFirstChar { it.uppercase() }
        val head = if (colour != null) "$face ($colour)" else face
        val depth = when {
            move.wide -> ", ${move.layer} layers"
            move.layer > 1 -> ", layer ${move.layer}"
            else -> ""
        }
        return "$head$depth, ${turnPhrase(move.direction)}"
    }
}
