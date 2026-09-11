package com.bigp.rubiksolver.cube.solver.twophase

import com.bigp.rubiksolver.cube.model.CubeSize
import com.bigp.rubiksolver.cube.model.CubeState
import com.bigp.rubiksolver.cube.model.Move
import com.bigp.rubiksolver.cube.solver.common.CubieCube
import com.bigp.rubiksolver.cube.solver.common.SolveOutcome
import com.bigp.rubiksolver.cube.solver.common.SolveProgress
import com.bigp.rubiksolver.cube.solver.common.Solver

/**
 * The 3x3 solver: Kociemba's two-phase algorithm.
 *
 * Phase 1 uses all eighteen quarter and half turns to reach the group
 * `<U, D, R2, L2, F2, B2>`; phase 2 finishes inside it. Both phases are iterative-deepening A*
 * driven by the pruning tables in [TwoPhaseTables].
 *
 * The search returns the first complete solution it finds rather than the shortest one. In practice
 * that lands around twenty turns, which is far below what a person would produce by hand and well
 * inside what is pleasant to follow move by move.
 */
class TwoPhaseSolver(
    /** Hard ceiling on total move count; also the point at which the search gives up. */
    private val maxLength: Int = 30,
) : Solver {

    override fun prepare(onProgress: (SolveProgress) -> Unit) = TwoPhaseTables.ensureBuilt(onProgress)

    override fun solve(state: CubeState, onProgress: (SolveProgress) -> Unit): SolveOutcome {
        require(state.size == CubeSize.THREE) { "TwoPhaseSolver only handles 3x3" }
        TwoPhaseTables.ensureBuilt(onProgress)
        val cube = try {
            CubieCube.fromState(state)
        } catch (e: IllegalArgumentException) {
            return SolveOutcome.Failed(e.message ?: "This scan is not a solvable cube.")
        }
        if (!cube.isReachable) {
            return SolveOutcome.Failed(
                "This is not a position a 3x3 can be turned into. Two stickers are probably " +
                    "swapped, or a corner or edge is showing the wrong way round."
            )
        }
        val moves = search(cube, onProgress) ?: return SolveOutcome.Failed(
            "Could not find a solution within $maxLength moves. The scan is probably not a " +
                "reachable cube state."
        )
        return SolveOutcome.Solved(moves.map { CubieCube.MOVE_ORDER[it] })
    }

    /** Solves a piece-level cube directly. Used by the big-cube solver after reduction. */
    fun searchCubie(cube: CubieCube): List<Move>? {
        TwoPhaseTables.ensureBuilt()
        if (!cube.isReachable) return null
        return search(cube) { }?.map { CubieCube.MOVE_ORDER[it] }
    }

    // ---- Search --------------------------------------------------------------------------

    private val path = IntArray(64)
    private var solution: IntArray? = null

    private fun search(cube: CubieCube, onProgress: (SolveProgress) -> Unit): List<Int>? {
        if (cube.isSolved) return emptyList()
        solution = null
        val twist = cube.twist()
        val flip = cube.flip()
        val slice = cube.udSlice()
        val lower = phase1Heuristic(twist, flip, slice)
        for (depth1 in lower..minOf(12, maxLength)) {
            onProgress(SolveProgress("Searching (depth $depth1)", 0.5f + depth1 * 0.03f))
            if (phase1(cube, twist, flip, slice, depth1, 0, -1)) {
                return solution!!.toList()
            }
        }
        return null
    }

    private fun phase1Heuristic(twist: Int, flip: Int, slice: Int): Int {
        val a = TwoPhaseTables.prunTwistSlice[twist * TwoPhaseTables.SLICE_COUNT + slice].toInt() and 0xFF
        val b = TwoPhaseTables.prunFlipSlice[flip * TwoPhaseTables.SLICE_COUNT + slice].toInt() and 0xFF
        return maxOf(a, b)
    }

    private fun phase2Heuristic(corner: Int, edge8: Int, slicePerm: Int): Int {
        val a = TwoPhaseTables.prunCornerSlice[corner * TwoPhaseTables.SLICE_PERM_COUNT + slicePerm].toInt() and 0xFF
        val b = TwoPhaseTables.prunEdge8Slice[edge8 * TwoPhaseTables.SLICE_PERM_COUNT + slicePerm].toInt() and 0xFF
        return maxOf(a, b)
    }

    /**
     * Depth-limited phase 1. [remaining] counts moves still allowed; on reaching zero the cube must
     * be inside the phase 2 group, and phase 2 takes over.
     */
    private fun phase1(
        root: CubieCube,
        twist: Int,
        flip: Int,
        slice: Int,
        remaining: Int,
        depth: Int,
        lastFace: Int,
    ): Boolean {
        if (remaining == 0) {
            if (twist != 0 || flip != 0 || slice != CubieCube.SOLVED_UD_SLICE) return false
            return startPhase2(root, depth, lastFace)
        }
        if (phase1Heuristic(twist, flip, slice) > remaining) return false
        for (m in 0 until 18) {
            val face = m / 3
            if (!allowed(face, lastFace)) continue
            path[depth] = m
            val nt = TwoPhaseTables.twistMove[twist][m]
            val nf = TwoPhaseTables.flipMove[flip][m]
            val ns = TwoPhaseTables.sliceMove[slice][m]
            if (phase1(root, nt, nf, ns, remaining - 1, depth + 1, face)) return true
        }
        return false
    }

    private fun startPhase2(root: CubieCube, depth: Int, lastFace: Int): Boolean {
        val cube = root.copy()
        for (i in 0 until depth) cube.applyInPlace(CubieCube.MOVES[path[i]])
        val corner = cube.cornerPermutation()
        val edge8 = cube.edge8Permutation()
        val slicePerm = cube.slicePermutation()
        val budget = minOf(18, maxLength - depth)
        val lower = phase2Heuristic(corner, edge8, slicePerm)
        for (length in lower..budget) {
            if (phase2(corner, edge8, slicePerm, length, depth, lastFace)) {
                solution = path.copyOfRange(0, depth + length)
                return true
            }
        }
        return false
    }

    private fun phase2(
        corner: Int,
        edge8: Int,
        slicePerm: Int,
        remaining: Int,
        depth: Int,
        lastFace: Int,
    ): Boolean {
        if (remaining == 0) return corner == 0 && edge8 == 0 && slicePerm == 0
        if (phase2Heuristic(corner, edge8, slicePerm) > remaining) return false
        val indices = CubieCube.PHASE2_MOVE_INDICES
        for (i in indices.indices) {
            val m = indices[i]
            val face = m / 3
            if (!allowed(face, lastFace)) continue
            path[depth] = m
            val nc = TwoPhaseTables.cornerPermMove[corner][i]
            val ne = TwoPhaseTables.edge8PermMove[edge8][i]
            val ns = TwoPhaseTables.slicePermMove[slicePerm][i]
            if (phase2(nc, ne, ns, remaining - 1, depth + 1, face)) return true
        }
        return false
    }

    /**
     * Drops move orders that only produce duplicate sequences: never turn the same face twice in a
     * row, and when two opposite faces are turned back to back, fix one of the two orderings.
     */
    private fun allowed(face: Int, lastFace: Int): Boolean {
        if (lastFace < 0) return true
        if (face == lastFace) return false
        // U/D are 0/3, R/L are 1/4, F/B are 2/5.
        if (face >= 3 && face - 3 == lastFace) return false
        return true
    }
}
