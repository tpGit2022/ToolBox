package com.bigp.rubiksolver.cube.solver.twobytwo

import com.bigp.rubiksolver.cube.model.CubeSize
import com.bigp.rubiksolver.cube.model.CubeState
import com.bigp.rubiksolver.cube.model.Move
import com.bigp.rubiksolver.cube.solver.common.Combinatorics
import com.bigp.rubiksolver.cube.solver.common.CubieCube
import com.bigp.rubiksolver.cube.solver.common.SolveOutcome
import com.bigp.rubiksolver.cube.solver.common.SolveProgress
import com.bigp.rubiksolver.cube.solver.common.Solver
import com.bigp.rubiksolver.cube.validation.CornerAnalysis
import com.bigp.rubiksolver.cube.validation.CubeScheme

/**
 * The 2x2 solver.
 *
 * A 2x2 has no centres, so one corner can be declared "already home" and never touched. Holding the
 * DBL corner still leaves only U, R and F turns, and the reachable space collapses to
 * `7! * 3^6 = 3,674,160` states. That is small enough to breadth-first search in full, so instead of
 * a heuristic search this builds an exact distance-to-solved table once and then walks downhill.
 * Every solution it returns is therefore **optimal**, and finding one is a handful of array reads.
 */
class TwoByTwoSolver : Solver {

    override fun prepare(onProgress: (SolveProgress) -> Unit) = Tables.ensureBuilt(onProgress)

    override fun solve(state: CubeState, onProgress: (SolveProgress) -> Unit): SolveOutcome {
        require(state.size == CubeSize.TWO) { "TwoByTwoSolver only handles 2x2" }
        Tables.ensureBuilt(onProgress)
        val scheme = CubeScheme.resolve(state)
            ?: return SolveOutcome.Failed("These stickers do not describe a real 2x2.")
        val corners = CornerAnalysis.of(state, scheme)
            ?: return SolveOutcome.Failed("One of the corners shows an impossible colour triple.")
        if (corners.permutation[FIXED_SLOT] != FIXED_SLOT || corners.orientation[FIXED_SLOT] != 0) {
            // CubeScheme pins the DBL corner, so this can only mean the state is inconsistent.
            return SolveOutcome.Failed("Could not orient this 2x2 scan.")
        }
        if (corners.permutation.toSortedSet().size != 8) {
            return SolveOutcome.Failed("Two corners show the same colours, so the scan is wrong.")
        }
        if (corners.orientationSum % 3 != 0) {
            return SolveOutcome.Failed("The corner twists do not add up to a solvable cube.")
        }

        var perm = permIndex(corners.permutation)
        var ori = oriIndex(corners.orientation)
        val out = ArrayList<Move>()
        var guard = 0
        while (!(perm == SOLVED_PERM && ori == SOLVED_ORI)) {
            val here = distance(perm, ori)
            var stepped = false
            for (m in 0 until MOVE_COUNT) {
                val np = Tables.permMove[perm][m]
                val no = Tables.oriMove[ori][m]
                if (distance(np, no) == here - 1) {
                    out += CubieCube.MOVE_ORDER[m]
                    perm = np
                    ori = no
                    stepped = true
                    break
                }
            }
            if (!stepped) return SolveOutcome.Failed("This 2x2 state cannot be reached by turning a cube.")
            if (++guard > 40) return SolveOutcome.Failed("2x2 search did not terminate.")
        }
        onProgress(SolveProgress("Solved", 1f))
        return SolveOutcome.Solved(out)
    }

    private fun distance(perm: Int, ori: Int): Int =
        Tables.distance[perm * ORI_COUNT + ori].toInt() and 0xFF

    companion object {
        /** The corner slot held still: DBL, index 6 in the standard slot order. */
        const val FIXED_SLOT = 6

        /** The seven slots that move, in a fixed order. */
        val FREE_SLOTS = intArrayOf(0, 1, 2, 3, 4, 5, 7)

        /** U, R and F in all three directions: the first nine entries of the standard move order. */
        const val MOVE_COUNT = 9

        const val PERM_COUNT = 5040
        const val ORI_COUNT = 729

        private val slotToIndex = IntArray(8) { -1 }.also {
            for (i in FREE_SLOTS.indices) it[FREE_SLOTS[i]] = i
        }

        val SOLVED_PERM: Int = Combinatorics.permutationIndex(IntArray(7) { it })
        const val SOLVED_ORI = 0

        /** Packs the seven moving corners' permutation into `0 until 5040`. */
        fun permIndex(cornerPermutation: IntArray): Int {
            val p = IntArray(7) { slotToIndex[cornerPermutation[FREE_SLOTS[it]]] }
            return Combinatorics.permutationIndex(p)
        }

        fun permFromIndex(index: Int): IntArray {
            val p = Combinatorics.permutationFromIndex(index, 7)
            val cp = IntArray(8)
            cp[FIXED_SLOT] = FIXED_SLOT
            for (i in 0 until 7) cp[FREE_SLOTS[i]] = FREE_SLOTS[p[i]]
            return cp
        }

        /** Packs corner twists into `0 until 729`; the seventh is implied by the other six. */
        fun oriIndex(orientation: IntArray): Int {
            var v = 0
            for (i in 0 until 6) v = v * 3 + orientation[FREE_SLOTS[i]]
            return v
        }

        fun oriFromIndex(index: Int): IntArray {
            val co = IntArray(8)
            var v = index
            var sum = 0
            for (i in 5 downTo 0) {
                val d = v % 3
                co[FREE_SLOTS[i]] = d
                sum += d
                v /= 3
            }
            co[FREE_SLOTS[6]] = (3 - sum % 3) % 3
            return co
        }
    }

    /** Move tables and the exact distance-to-solved table, built once per process. */
    object Tables {
        lateinit var permMove: Array<IntArray>; private set
        lateinit var oriMove: Array<IntArray>; private set
        lateinit var distance: ByteArray; private set

        @Volatile
        private var built = false
        val isBuilt: Boolean get() = built

        @Synchronized
        fun ensureBuilt(onProgress: (SolveProgress) -> Unit = {}) {
            if (built) return
            onProgress(SolveProgress("Building 2x2 tables", 0.1f))
            permMove = Array(PERM_COUNT) { index ->
                val cp = permFromIndex(index)
                IntArray(MOVE_COUNT) { m ->
                    val move = CubieCube.MOVES[m]
                    val next = IntArray(8) { cp[move.cp[it]] }
                    permIndex(next)
                }
            }
            oriMove = Array(ORI_COUNT) { index ->
                val co = oriFromIndex(index)
                IntArray(MOVE_COUNT) { m ->
                    val move = CubieCube.MOVES[m]
                    val next = IntArray(8) { (co[move.cp[it]] + move.co[it]) % 3 }
                    oriIndex(next)
                }
            }
            onProgress(SolveProgress("Mapping every 2x2 state", 0.4f))
            distance = breadthFirst()
            built = true
            onProgress(SolveProgress("Ready", 1f))
        }

        private fun breadthFirst(): ByteArray {
            val size = PERM_COUNT * ORI_COUNT
            val dist = ByteArray(size).also { it.fill(-1) }
            val start = SOLVED_PERM * ORI_COUNT + SOLVED_ORI
            dist[start] = 0
            val queue = IntArray(size)
            queue[0] = start
            var head = 0
            var tail = 1
            while (head < tail) {
                val state = queue[head++]
                val depth = dist[state] + 1
                val rowP = permMove[state / ORI_COUNT]
                val rowO = oriMove[state % ORI_COUNT]
                for (m in 0 until MOVE_COUNT) {
                    val next = rowP[m] * ORI_COUNT + rowO[m]
                    if (dist[next].toInt() == -1) {
                        dist[next] = depth.toByte()
                        queue[tail++] = next
                    }
                }
            }
            return dist
        }
    }
}
