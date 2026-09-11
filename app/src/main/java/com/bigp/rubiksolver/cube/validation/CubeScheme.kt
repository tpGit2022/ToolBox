package com.bigp.rubiksolver.cube.validation

import com.bigp.rubiksolver.cube.model.CubeState
import com.bigp.rubiksolver.cube.model.FaceId
import com.bigp.rubiksolver.cube.model.FaceletColor
import com.bigp.rubiksolver.cube.model.Geometry

/**
 * Which sticker colour belongs on which face for one particular scanned cube.
 *
 * The app never assumes a colour scheme. On odd cubes the fixed centres give it away directly. On
 * even cubes there is no fixed centre, so the cubie sitting in the DBL corner is *declared* to be
 * home; that picks one of the 24 equivalent solved orientations, which is all a solver needs.
 */
data class CubeScheme(
    val colorOf: Map<FaceId, FaceletColor>,
    val faceOf: Map<FaceletColor, FaceId>,
) {
    fun solvedState(state: CubeState): CubeState = CubeState.solved(state.size, colorOf)

    companion object {
        /**
         * Works out the scheme, or returns null when the stickers cannot describe a real cube
         * (colours that should be opposite appearing on one corner, duplicated centres, and so on).
         */
        fun resolve(state: CubeState): CubeScheme? {
            val n = state.n
            val opposites = opposingPairs(state) ?: return null
            val colorOf = HashMap<FaceId, FaceletColor>(6)
            if (n % 2 == 1) {
                val mid = n / 2
                for (face in FaceId.entries) colorOf[face] = state.colorAt(face, mid, mid)
            } else {
                // Declare the cubie currently in the DBL corner to be home.
                val dbl = Geometry.CORNER_SLOTS.indexOfFirst {
                    it.first == FaceId.D && it.second == FaceId.B && it.third == FaceId.L
                }
                val facelets = Geometry.cornerFacelets(dbl, n)
                val slot = Geometry.CORNER_SLOTS[dbl]
                val faces = listOf(slot.first, slot.second, slot.third)
                for (i in 0 until 3) colorOf[faces[i]] = state.colorAt(facelets[i])
                for (face in faces) {
                    colorOf[face.opposite] = opposites.getValue(colorOf.getValue(face))
                }
            }
            if (colorOf.size != 6) return null
            if (colorOf.values.toSet().size != 6) return null
            for (face in FaceId.entries) {
                if (opposites[colorOf.getValue(face)] != colorOf[face.opposite]) return null
            }
            val faceOf = colorOf.entries.associate { (f, c) -> c to f }
            return CubeScheme(colorOf, faceOf)
        }

        /**
         * Pairs up colours that never share a corner cubie. A real cube always yields exactly three
         * such pairs.
         */
        fun opposingPairs(state: CubeState): Map<FaceletColor, FaceletColor>? {
            val n = state.n
            val together = Array(6) { BooleanArray(6) }
            for (slot in Geometry.CORNER_SLOTS.indices) {
                val f = Geometry.cornerFacelets(slot, n)
                val cs = IntArray(3) { state.ordinalAt(f[it]) }
                if (cs[0] == cs[1] || cs[1] == cs[2] || cs[0] == cs[2]) return null
                for (a in cs) for (b in cs) if (a != b) together[a][b] = true
            }
            val out = HashMap<FaceletColor, FaceletColor>(6)
            for (a in 0 until 6) {
                val candidates = (0 until 6).filter { it != a && !together[a][it] }
                if (candidates.size != 1) return null
                out[FaceletColor.ALL[a]] = FaceletColor.ALL[candidates[0]]
            }
            // Must be a perfect matching.
            for ((k, v) in out) if (out[v] != k) return null
            return out
        }
    }
}
