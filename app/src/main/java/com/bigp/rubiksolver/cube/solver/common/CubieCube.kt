package com.bigp.rubiksolver.cube.solver.common

import com.bigp.rubiksolver.cube.model.CubeSize
import com.bigp.rubiksolver.cube.model.CubeState
import com.bigp.rubiksolver.cube.model.Direction
import com.bigp.rubiksolver.cube.model.FaceId
import com.bigp.rubiksolver.cube.model.Move
import com.bigp.rubiksolver.cube.validation.CornerAnalysis
import com.bigp.rubiksolver.cube.validation.CubeScheme
import com.bigp.rubiksolver.cube.validation.EdgeAnalysis

/**
 * A 3x3 at the piece level: which cubie sits in each slot, and how it is turned.
 *
 * `cp[i]` is the corner cubie occupying slot `i`, `co[i]` its clockwise twist in thirds. `ep` and
 * `eo` say the same for the twelve edges, with `eo[i]` a flip flag. Slot order is the standard
 * URF, UFL, ULB, UBR, DFR, DLF, DBL, DRB / UR, UF, UL, UB, DR, DF, DL, DB, FR, FL, BL, BR.
 *
 * The six basic move cubes are **derived** from
 * [com.bigp.rubiksolver.cube.model.MoveEngine] rather than typed in from a reference, so they
 * cannot disagree with the facelet engine that the renderer and the big-cube solver use.
 */
class CubieCube(
    val cp: IntArray = IntArray(8) { it },
    val co: IntArray = IntArray(8),
    val ep: IntArray = IntArray(12) { it },
    val eo: IntArray = IntArray(12),
) {

    fun copy(): CubieCube = CubieCube(cp.copyOf(), co.copyOf(), ep.copyOf(), eo.copyOf())

    /**
     * Applies [move] to this cube, in place.
     *
     * `move.cp[i]` names the slot whose contents land in slot `i`, matching the facelet convention
     * `next[i] = current[perm[i]]`.
     */
    fun applyInPlace(move: CubieCube) {
        val ncp = IntArray(8)
        val nco = IntArray(8)
        for (i in 0 until 8) {
            ncp[i] = cp[move.cp[i]]
            nco[i] = (co[move.cp[i]] + move.co[i]) % 3
        }
        val nep = IntArray(12)
        val neo = IntArray(12)
        for (i in 0 until 12) {
            nep[i] = ep[move.ep[i]]
            neo[i] = (eo[move.ep[i]] + move.eo[i]) % 2
        }
        ncp.copyInto(cp); nco.copyInto(co); nep.copyInto(ep); neo.copyInto(eo)
    }

    fun applied(move: CubieCube): CubieCube = copy().also { it.applyInPlace(move) }

    val isSolved: Boolean
        get() = cp.contentEquals(IntArray(8) { it }) && co.all { it == 0 } &&
            ep.contentEquals(IntArray(12) { it }) && eo.all { it == 0 }

    /**
     * True when turning a real 3x3 could produce this position.
     *
     * This has to be checked before searching, and it is not optional. The twist coordinate is
     * built from seven of the eight corners and the flip coordinate from eleven of the twelve
     * edges, each leaving the last piece implied. A position whose only fault is that last piece --
     * one corner twisted on its own, or one edge flipped on its own -- therefore reads as
     * *coordinate-solved*, and the search happily returns a move list that leaves the cube
     * unsolved. Big-cube reduction produces exactly those positions, which is what parity means
     * there, so the gate is what turns a wrong answer into an honest "not reachable".
     */
    val isReachable: Boolean
        get() {
            if (cp.toSortedSet().size != 8 || ep.toSortedSet().size != 12) return false
            if (co.sum() % 3 != 0) return false
            if (eo.sum() % 2 != 0) return false
            return permutationParity(cp) == permutationParity(ep)
        }

    // ---- Phase 1 coordinates -------------------------------------------------------------

    /** Corner twist, `0 until 2187`. The eighth corner is implied by the others. */
    fun twist(): Int {
        var t = 0
        for (i in 0 until 7) t = t * 3 + co[i]
        return t
    }

    /** Edge flip, `0 until 2048`. The twelfth edge is implied by the others. */
    fun flip(): Int {
        var f = 0
        for (i in 0 until 11) f = f * 2 + eo[i]
        return f
    }

    /** Which four slots hold the FR, FL, BL and BR edges, `0 until 495`. */
    fun udSlice(): Int {
        val occupied = IntArray(4)
        var k = 0
        for (slot in 0 until 12) if (ep[slot] >= 8) occupied[k++] = slot
        return Combinatorics.combinationIndex(occupied)
    }

    // ---- Phase 2 coordinates -------------------------------------------------------------

    /** Corner permutation, `0 until 40320`. */
    fun cornerPermutation(): Int = Combinatorics.permutationIndex(cp)

    /** Permutation of the eight U and D layer edges, `0 until 40320`. Phase 2 only. */
    fun edge8Permutation(): Int = Combinatorics.permutationIndex(IntArray(8) { ep[it] })

    /** Permutation of the four middle-slice edges among themselves, `0 until 24`. Phase 2 only. */
    fun slicePermutation(): Int = Combinatorics.permutationIndex(IntArray(4) { ep[8 + it] - 8 })

    companion object {
        val SOLVED_UD_SLICE: Int = CubieCube().udSlice()

        /** 0 for an even permutation, 1 for an odd one. */
        fun permutationParity(perm: IntArray): Int {
            val a = perm.copyOf()
            var swaps = 0
            for (i in a.indices) {
                while (a[i] != i) {
                    val j = a[i]
                    val t = a[j]
                    a[j] = a[i]
                    a[i] = t
                    swaps++
                }
            }
            return swaps % 2
        }

        fun setTwist(value: Int): CubieCube {
            val c = CubieCube()
            var v = value
            var sum = 0
            for (i in 6 downTo 0) {
                c.co[i] = v % 3
                sum += c.co[i]
                v /= 3
            }
            c.co[7] = ((3 - sum % 3) % 3)
            return c
        }

        fun setFlip(value: Int): CubieCube {
            val c = CubieCube()
            var v = value
            var sum = 0
            for (i in 10 downTo 0) {
                c.eo[i] = v % 2
                sum += c.eo[i]
                v /= 2
            }
            c.eo[11] = sum % 2
            return c
        }

        fun setUdSlice(value: Int): CubieCube {
            val c = CubieCube()
            val slots = Combinatorics.combinationFromIndex(value, 12, 4)
            val slotSet = slots.toHashSet()
            var sliceNext = 8
            var otherNext = 0
            for (slot in 0 until 12) {
                c.ep[slot] = if (slot in slotSet) sliceNext++ else otherNext++
            }
            return c
        }

        fun setCornerPermutation(value: Int): CubieCube =
            CubieCube(cp = Combinatorics.permutationFromIndex(value, 8))

        fun setEdge8Permutation(value: Int): CubieCube {
            val c = CubieCube()
            val p = Combinatorics.permutationFromIndex(value, 8)
            for (i in 0 until 8) c.ep[i] = p[i]
            return c
        }

        fun setSlicePermutation(value: Int): CubieCube {
            val c = CubieCube()
            val p = Combinatorics.permutationFromIndex(value, 4)
            for (i in 0 until 4) c.ep[8 + i] = 8 + p[i]
            return c
        }

        /** Move index order used by every table here: U U2 U' R R2 R' F F2 F' D D2 D' L L2 L' B B2 B'. */
        val MOVE_ORDER: List<Move> = listOf(FaceId.U, FaceId.R, FaceId.F, FaceId.D, FaceId.L, FaceId.B)
            .flatMap { face ->
                listOf(Direction.CW, Direction.DOUBLE, Direction.CCW).map { Move(face, 1, false, it) }
            }

        /** The 18 basic move cubes, index-aligned with [MOVE_ORDER]. */
        val MOVES: Array<CubieCube> by lazy {
            Array(MOVE_ORDER.size) { fromState(CubeState.solved(CubeSize.THREE).apply(MOVE_ORDER[it])) }
        }

        /** Phase 2 keeps the cube inside the group generated by U, D, R2, L2, F2 and B2. */
        val PHASE2_MOVE_INDICES: IntArray = intArrayOf(0, 1, 2, 4, 7, 9, 10, 11, 13, 16)

        /**
         * Reads a 3x3 [CubeState] as a [CubieCube], or returns null if the stickers do not describe
         * a real cube. Callers normally validate first; this is the second line of defence.
         */
        fun fromState(state: CubeState, scheme: CubeScheme? = null): CubieCube {
            require(state.size == CubeSize.THREE) { "CubieCube models a 3x3, got ${state.size.label}" }
            val s = scheme ?: CubeScheme.resolve(state)
                ?: throw IllegalArgumentException("cube state has no consistent colour scheme")
            val corners = CornerAnalysis.of(state, s)
                ?: throw IllegalArgumentException("cube state has an impossible corner")
            val edges = EdgeAnalysis.of(state, s)
                ?: throw IllegalArgumentException("cube state has an impossible edge")
            return CubieCube(corners.permutation, corners.orientation, edges.permutation, edges.orientation)
        }
    }
}
