package com.bigp.rubiksolver.cube.solver.reduction

import com.bigp.rubiksolver.cube.model.Move
import com.bigp.rubiksolver.cube.model.MoveEngine

/**
 * A choice of moves for one position in a candidate sequence. `null` stands for "skip this
 * position", which lets one shaped family cover several sequence lengths at once.
 */
typealias MoveSlot = List<Move?>

/**
 * One candidate family: what may go in each position, plus which positions are forced to undo an
 * earlier one.
 *
 * @param slots what each position may hold.
 * @param mirrors for each position, the index of an earlier position this one must invert, or -1
 *   when it is free. A conjugate `x · A · x⁻¹` is the shape that makes big-cube work possible, and
 *   pinning the closing move instead of searching it cuts the space by the width of that slot.
 */
class Shape(val slots: List<MoveSlot>, val mirrors: IntArray = IntArray(slots.size) { -1 }) {
    init {
        require(mirrors.size == slots.size) { "one mirror entry per slot" }
        for (i in mirrors.indices) {
            require(mirrors[i] < i) { "slot $i must mirror an earlier slot" }
        }
    }

    val size: Int get() = slots.size
}

/**
 * Depth-first search over shaped move families, used by the big-cube reduction stages.
 *
 * Every stage asks the same question: what short sequence makes this measurably better without
 * undoing anything already finished? Rather than trusting remembered human algorithms, each
 * candidate is applied to a real cube and checked, so a sequence is only accepted because it
 * demonstrably worked.
 *
 * Shape matters enormously. On a big cube, only inner slices and wide turns move centre pieces
 * between faces, so anything that must leave the centres finished has to put those turns back. That
 * makes the useful families conjugates — `x · A · x⁻¹` — and searching exactly those instead of
 * every sequence of the same length is the difference between milliseconds and hours.
 *
 * Facelets stay as raw `IntArray`s with pre-allocated scratch levels; this inner loop runs tens of
 * millions of times per solve.
 */
class StageSearch(private val n: Int, maxDepth: Int) {

    private val faceletCount = 6 * n * n
    private val levels = Array(maxDepth + 2) { IntArray(faceletCount) }
    private val chosen = arrayOfNulls<Move>(maxDepth + 2)
    private var currentDepth = 0

    /**
     * Local, unsynchronised permutation cache. [MoveEngine]'s own cache is shared and locked, which
     * is fine per solve but not at millions of lookups a second.
     */
    private val permCache = HashMap<Move, IntArray>()

    private fun permutationOf(move: Move): IntArray =
        permCache.getOrPut(move) { MoveEngine.permutation(move, n) }

    /** Leaves visited by the most recent [find]. Diagnostics only. */
    var lastLeafCount: Long = 0L
        private set

    /**
     * Tries every sequence [shape] describes, in order, and returns the first one [accept] approves
     * of.
     *
     * @param accept receives the resulting facelet array. It must not retain or mutate it.
     */
    fun find(start: IntArray, shape: Shape, accept: (IntArray) -> Boolean): List<Move>? {
        require(shape.size + 1 < levels.size) { "shape is deeper than this searcher was built for" }
        start.copyInto(levels[0])
        currentDepth = shape.size
        lastLeafCount = 0L
        if (!dfs(0, shape, 0, accept)) return null
        return currentSequence()
    }

    /**
     * The sequence that produced the position being judged. Valid only inside an `accept` callback,
     * which lets a caller sample from every match in one pass instead of taking only the first.
     */
    fun currentSequence(): List<Move> = (0 until currentDepth).mapNotNull { chosen[it] }

    private fun dfs(level: Int, shape: Shape, lastLayer: Int, accept: (IntArray) -> Boolean): Boolean {
        val src = levels[level]
        val dst = levels[level + 1]
        val mirror = shape.mirrors[level]
        // A mirrored slot has exactly one legal move, so it is walked without building a list;
        // this runs once per leaf and allocation here would dominate the search.
        val slot: List<Move?>? = if (mirror >= 0) null else shape.slots[level]
        val width = slot?.size ?: 1
        for (i in 0 until width) {
            val move = if (slot != null) slot[i] else chosen[mirror]?.inverse
            val nextLayer: Int
            if (move == null) {
                src.copyInto(dst)
                nextLayer = lastLayer
            } else {
                val key = layerKey(move)
                // Two turns in a row on one layer set always collapse into a single turn.
                if (key == lastLayer) continue
                val perm = permutationOf(move)
                for (k in dst.indices) dst[k] = src[perm[k]]
                nextLayer = key
            }
            chosen[level] = move
            if (level == shape.size - 1) {
                lastLeafCount++
                if (accept(dst)) return true
            } else if (dfs(level + 1, shape, nextLayer, accept)) {
                return true
            }
        }
        return false
    }

    private fun layerKey(m: Move): Int = (m.face.ordinal * 64) + (m.layer * 2) + (if (m.wide) 1 else 0)
}
