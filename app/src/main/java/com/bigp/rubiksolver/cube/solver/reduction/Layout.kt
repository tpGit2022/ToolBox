package com.bigp.rubiksolver.cube.solver.reduction

import com.bigp.rubiksolver.cube.model.CubeSize
import com.bigp.rubiksolver.cube.model.CubeState
import com.bigp.rubiksolver.cube.model.FaceId
import com.bigp.rubiksolver.cube.model.Geometry
import com.bigp.rubiksolver.cube.validation.CubeScheme

/**
 * Pre-computed facelet indices and expected colours for one cube size and colour scheme.
 *
 * The reduction search evaluates tens of millions of candidate positions, so every geometry lookup
 * is resolved once here and reduced to flat integer arrays.
 */
class Layout(val n: Int, val scheme: CubeScheme) {

    /** Centre-block facelet indices per face, and the colour each one should end up. */
    private val centreIndicesByFace: Array<IntArray> =
        Array(6) { Geometry.centreFacelets(FaceId.entries[it], n) }
    private val centreColourByFace: IntArray =
        IntArray(6) { scheme.colorOf.getValue(FaceId.entries[it]).ordinal }

    private val allCentreIndices: IntArray = centreIndicesByFace.flatMap { it.toList() }.toIntArray()
    private val allCentreColours: IntArray = IntArray(allCentreIndices.size).also { out ->
        var k = 0
        for (f in 0 until 6) repeat(centreIndicesByFace[f].size) { out[k++] = centreColourByFace[f] }
    }

    /** Facelet index of each edge piece, on the first and second face of its edge slot. */
    private val edgeA: Array<IntArray> =
        Array(12) { slot -> Geometry.edgeStripFacelets(slot, n).map { it[0] }.toIntArray() }
    private val edgeB: Array<IntArray> =
        Array(12) { slot -> Geometry.edgeStripFacelets(slot, n).map { it[1] }.toIntArray() }

    val centrePiecesPerFace: Int = centreIndicesByFace[0].size

    /** Every centre sticker on the cube; the goal for stage one. */
    val totalCentreTarget: Int = allCentreIndices.size

    /** Index of each face's very middle sticker, or empty on an even cube, which has none. */
    private val middleIndex: IntArray =
        if (n % 2 == 1) IntArray(6) { it * n * n + (n / 2) * n + (n / 2) } else IntArray(0)

    /**
     * Correct centre stickers across the whole cube.
     *
     * On an odd cube the target is each face's *own* middle sticker rather than a colour fixed in
     * advance. The six middles are rigidly linked through the core, but an inner slice turns the
     * whole frame -- so a scan's colour scheme is only true of the position it was read from.
     * Scoring against a fixed scheme means a cube with every centre block finished can still read
     * as six stickers short, purely because the frame has turned, and the stage then stalls forever
     * chasing an arrangement it has already reached. Scoring against the live middles asks the
     * question that actually matters: is each block one colour?
     */
    fun totalCentreScore(facelets: IntArray): Int {
        var score = 0
        if (middleIndex.isNotEmpty()) {
            for (face in 0 until 6) {
                val target = facelets[middleIndex[face]]
                for (index in centreIndicesByFace[face]) if (facelets[index] == target) score++
            }
            return score
        }
        for (i in allCentreIndices.indices) {
            if (facelets[allCentreIndices[i]] == allCentreColours[i]) score++
        }
        return score
    }

    /** How many centre stickers differ between two positions. */
    fun centreMismatchCount(a: IntArray, b: IntArray): Int {
        var count = 0
        for (index in allCentreIndices) if (a[index] != b[index]) count++
        return count
    }

    /** Correct centre stickers on one face. */
    fun centreScore(facelets: IntArray, face: FaceId): Int {
        val indices = centreIndicesByFace[face.ordinal]
        val colour = centreColourByFace[face.ordinal]
        var score = 0
        for (i in indices) if (facelets[i] == colour) score++
        return score
    }

    fun faceCentreSolved(facelets: IntArray, face: FaceId): Boolean {
        val indices = centreIndicesByFace[face.ordinal]
        val colour = centreColourByFace[face.ordinal]
        for (i in indices) if (facelets[i] != colour) return false
        return true
    }

    /** True when every face in [faces] still has a finished centre block. */
    fun centresSolved(facelets: IntArray, faces: List<FaceId>): Boolean {
        for (face in faces) if (!faceCentreSolved(facelets, face)) return false
        return true
    }

    /**
     * True when every centre block is a single colour.
     *
     * Deliberately says nothing about *which* colour. A finished cube in a turned frame is still a
     * finished cube, and the 3x3 stage reads the scheme back off the position anyway.
     */
    fun allCentresSolved(facelets: IntArray): Boolean {
        for (face in 0 until 6) {
            val indices = centreIndicesByFace[face]
            val first = facelets[indices[0]]
            for (index in indices) if (facelets[index] != first) return false
        }
        return true
    }

    /** True when every piece of edge [slot] reads the same two colours the same way round. */
    fun edgePaired(facelets: IntArray, slot: Int): Boolean {
        val a = edgeA[slot]
        val b = edgeB[slot]
        val firstA = facelets[a[0]]
        val firstB = facelets[b[0]]
        for (k in 1 until a.size) {
            if (facelets[a[k]] != firstA || facelets[b[k]] != firstB) return false
        }
        return true
    }

    /**
     * How close edge [slot] is to reading as one edge: 2 when every piece agrees, 1 when the pieces
     * carry the right pair of colours but disagree on which way round, 0 otherwise.
     *
     * The middle value is what makes the stage finishable. Counting only finished edges gives a
     * score that the last two edges cannot move -- gathering a wing into place always costs another
     * one, so nothing ever scores better and the search stalls with ten of twelve done. Crediting a
     * half-built edge restores the gradient, and turning it the right way round becomes its own,
     * separately reachable step.
     */
    fun edgeQuality(facelets: IntArray, slot: Int): Int {
        val a = edgeA[slot]
        val b = edgeB[slot]
        val firstA = facelets[a[0]]
        val firstB = facelets[b[0]]
        var paired = true
        var sameColours = true
        for (k in 1 until a.size) {
            val nextA = facelets[a[k]]
            val nextB = facelets[b[k]]
            if (nextA != firstA || nextB != firstB) paired = false
            if (!((nextA == firstA && nextB == firstB) || (nextA == firstB && nextB == firstA))) {
                sameColours = false
            }
        }
        return if (paired) 2 else if (sameColours) 1 else 0
    }

    /** Sum of [edgeQuality] over all twelve edges; [EDGE_TARGET] means every edge is finished. */
    fun edgeQualityScore(facelets: IntArray): Int {
        var total = 0
        for (slot in 0 until 12) total += edgeQuality(facelets, slot)
        return total
    }

    fun pairedEdgeCount(facelets: IntArray): Int {
        var count = 0
        for (slot in 0 until 12) if (edgePaired(facelets, slot)) count++
        return count
    }

    /**
     * Collapses a reduced big cube onto the 3x3 it now behaves like. Corners stay corners, any one
     * piece stands in for its whole edge (they all read alike once paired) and any centre facelet
     * stands in for the block.
     */
    fun projectTo3x3(state: CubeState): CubeState {
        val rows = intArrayOf(0, 1, n - 1)
        val cols = intArrayOf(0, 1, n - 1)
        val out = IntArray(6 * 9)
        for (face in FaceId.entries) {
            for (r in 0 until 3) for (c in 0 until 3) {
                out[face.ordinal * 9 + r * 3 + c] =
                    state.ordinalAt(face.ordinal * n * n + rows[r] * n + cols[c])
            }
        }
        return CubeState.fromOrdinals(CubeSize.THREE, out)
    }

    companion object {
        /** Every edge reading as one edge: twelve edges at a quality of two each. */
        const val EDGE_TARGET = 24
    }
}
