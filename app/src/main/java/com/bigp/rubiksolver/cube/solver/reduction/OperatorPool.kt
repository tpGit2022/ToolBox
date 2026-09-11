package com.bigp.rubiksolver.cube.solver.reduction

import com.bigp.rubiksolver.cube.model.CubeSize
import com.bigp.rubiksolver.cube.model.CubeState
import com.bigp.rubiksolver.cube.model.Move
import com.bigp.rubiksolver.cube.model.MoveEngine
import com.bigp.rubiksolver.cube.model.inverseAlgorithm
import com.bigp.rubiksolver.cube.validation.CubeScheme

/**
 * A short sequence, boiled down to the single facelet permutation it performs.
 *
 * [perm] satisfies `next[i] = current[perm[i]]`, so trying an operator costs one pass over the
 * facelets instead of one pass per move. [centreSupport] counts how many centre stickers it
 * disturbs on a finished cube; zero means it is safe to use once the centres are done.
 */
class Operator(
    val moves: List<Move>,
    val perm: IntArray,
    val centreSupport: Int,
    /** How many edge stickers it disturbs. Small means fine-grained, which the endgame needs. */
    val edgeSupport: Int,
)

/**
 * The sequences worth trying during reduction, worked out once per cube size.
 *
 * This exists because of a cost problem. Searching a family of a few million shaped sequences takes
 * seconds, and both reduction stages need to search one at *every* step -- hundreds of times per
 * solve. But which sequences are worth trying does not depend on the position at all:
 *
 *  - Whether a sequence leaves the centres finished is a property of the sequence, once the centres
 *    are finished. Every centre block is one colour, so all that matters is whether the sequence
 *    maps each block back onto itself.
 *  - How many pieces a sequence disturbs is likewise fixed. The endgame of a stage needs operations
 *    that move three pieces and leave the rest alone; anything with a wide footprint trades away as
 *    much as it gains.
 *
 * So the families are enumerated once against a solved cube, each surviving sequence is flattened
 * into a single permutation, and the stages then sweep a few thousand of those per step instead of
 * re-walking millions of dead ends. That is the difference between a solve taking minutes and
 * taking well under a second.
 */
class OperatorPool private constructor(
    /** Leave the centres finished and move at least one edge piece: for stage two. */
    val centreSafe: List<Operator>,
    /** Move a handful of centre pieces and nothing else: for stage one. */
    val narrowCentre: List<Operator>,
    /**
     * Three-cycles of centre pieces, obtained by composing pairs of the narrowest operators.
     *
     * The enumerated families cannot produce one directly -- on a 5x5 the narrowest thing they
     * reach disturbs four centre pieces. Four is too many at the end of the stage, where three
     * pieces are out of place and any wider operation gives back what it gains. Two operators run
     * back to back can cancel down to a three-cycle, and finding those costs one pass over a few
     * hundred squared pairs.
     */
    val centreFine: List<Operator>,
    /**
     * Operators that unpair exactly one or two edges on a finished cube -- so, run the other way,
     * exactly what repairs a cube with one or two edges left.
     *
     * This is the last-two-edges case, and no single enumerated operator can do it. The narrow ones
     * are three-cycles, which touch three edges at once; finishing needs four wings moved inside
     * two edges. That is an even permutation, so a pair of three-cycles reaches it and one cannot.
     */
    val edgeFinishers: List<Operator>,
) {

    companion object {

        /** Most centre stickers an operator may disturb and still count as a fine-grained one. */
        private const val NARROW_CENTRE_LIMIT = 9

        /** Ceiling on each list, so a generous family cannot blow up memory. */
        private const val MAX_PER_LIST = 9000

        /**
         * The two long-known parity algorithms and their inverses. `ParityAlgorithmTest` applies
         * each to a solved cube and asserts what it does, so they are used as measured, not
         * remembered.
         */
        /**
         * Pairing algorithms seeded into the finisher pool, in both notations that reach them.
         *
         * These are what a person uses on the last two edges, and they are the one thing here that
         * enumeration cannot supply: nine turns is far past what a search of this shape covers.
         * `EdgeAlgProbeTest` measures every one of them on a solved cube, and [buildEdgeFinishers]
         * keeps a seed only if it really leaves the centres whole and at most two edges apart -- so
         * a wrong line here is dropped rather than trusted.
         */
        private val SEED_FINISHERS: List<List<Move>> = listOf(
            "Dw R F' U R' F Dw'",
            "2D R F' U R' F 2D'",
            "Uw' R U R' F R' F' R Uw",
            "Uw R U R' F R' F' R Uw'",
            "Uw2 R U R' F R' F' R Uw2",
            "2U' R U R' F R' F' R 2U",
            "Lw U F' U' F Lw'",
            "2L U F' U' F 2L'",
            "3Uw' R U R' F R' F' R 3Uw",
            "3Uw R U R' F R' F' R 3Uw'",
            "3U' R U R' F R' F' R 3U",
            "3D R F' U R' F 3D'",
            "3Dw R F' U R' F 3Dw'",
            "Uw' R U2 R' F R' F' R Uw",
            "Dw R2 F' U R' F Dw'",
        ).map { Move.parseAlgorithm(it) }

        private val PARITY_ALGORITHMS: List<List<Move>> = run {
            val flip = Move.parseAlgorithm("2R2 B2 U2 2L U2 2R' U2 2R U2 F2 2R F2 2L' B2 2R2")
            val swap = Move.parseAlgorithm("2R2 U2 2R2 Uw2 2R2 Uw2")
            listOf(flip, flip.inverseAlgorithm(), swap, swap.inverseAlgorithm())
        }

        private val cache = HashMap<Int, OperatorPool>()

        @Synchronized
        fun forSize(size: CubeSize): OperatorPool = cache.getOrPut(size.n) { build(size) }

        private fun build(size: CubeSize): OperatorPool {
            val n = size.n
            val solved = CubeState.solved(size)
            val plan = Layout(n, CubeScheme.resolve(solved)!!)
            val search = StageSearch(n, 8)

            val outer: MoveSlot = MoveEngine.outerMoves()
            val carrier: MoveSlot = MoveEngine.innerSliceMoves(n) + wideMoves(n)
            val skip: MoveSlot = listOf(null)

            // Setups are applied separately at search time, so they are left out here: an outer
            // turn in front of an operator is free to try and would multiply this enumeration by
            // nineteen for nothing.
            val families = listOf(
                conjugate(skip, carrier, outer, carrier),
                commutator(skip, carrier, outer),
                commutator(skip, outer, carrier),
                // Two slices commuted against each other. This is the family that produces a plain
                // three-cycle of centre pieces, which nothing built from a slice and a face turn
                // does -- those move four at a time, and four is one too many at the end of the
                // centres, where three pieces are left out of place.
                commutator(skip, carrier, carrier),
                conjugate(skip, carrier, carrier, outer, carrier),
                conjugate(skip, carrier, outer, outer, carrier),
                conjugate(skip, carrier, outer, outer, outer, carrier),
            )

            val centreSafe = ArrayList<Operator>()
            val narrowCentre = ArrayList<Operator>()
            val seen = HashSet<String>()
            val reference = solved.toIntArray()

            for (family in families) {
                if (centreSafe.size >= MAX_PER_LIST && narrowCentre.size >= MAX_PER_LIST) break
                search.find(reference, family) { candidate ->
                    val support = centreSupport(candidate, plan, reference, n)
                    val edges = edgeSupport(candidate, reference, n)
                    if ((support == 0 && edges > 0) || (support in 1..NARROW_CENTRE_LIMIT)) {
                        val moves = search.currentSequence()
                        val key = moves.joinToString(" ")
                        if (seen.add(key)) {
                            val op = Operator(moves, permutationOf(moves, n), support, edges)
                            if (support == 0 && edges > 0 && centreSafe.size < MAX_PER_LIST) {
                                centreSafe += op
                            }
                            if (support in 1..NARROW_CENTRE_LIMIT && narrowCentre.size < MAX_PER_LIST) {
                                narrowCentre += op
                            }
                        }
                    }
                    false
                }
            }
            // The two parity algorithms belong in the pool as well. They are the only sequences
            // here that change which parity class the reduction lands in, and the last two edges of
            // a 4x4 sometimes need exactly that -- no conjugate or commutator of this length can
            // reach it, because they all use an even number of slice turns and the class does not
            // move unless that count is odd.
            for (alg in PARITY_ALGORITHMS) {
                val perm = permutationOf(alg, n)
                val moved = IntArray(reference.size) { reference[perm[it]] }
                val support = centreSupport(moved, plan, reference, n)
                if (support != 0) continue
                val edges = edgeSupport(moved, reference, n)
                if (edges == 0) continue
                if (seen.add(alg.joinToString(" "))) {
                    centreSafe += Operator(alg, perm, 0, edges)
                }
            }

            // Narrowest first: an operator that disturbs less is both likelier to help late in a
            // stage and shorter, and short operators make a solve a person can follow.
            centreSafe.sortWith(compareBy({ it.edgeSupport }, { it.moves.size }))
            narrowCentre.sortWith(compareBy({ it.centreSupport }, { it.moves.size }))
            val centreFine = refineCentre(narrowCentre, plan, reference)
            val finishers = buildEdgeFinishers(centreSafe, plan, reference, n)
            return OperatorPool(centreSafe, narrowCentre, centreFine, finishers)
        }

        private fun centreSupport(
            candidate: IntArray,
            plan: Layout,
            reference: IntArray,
            n: Int,
        ): Int = plan.centreMismatchCount(candidate, reference)

        /** Edge stickers disturbed. A piece on exactly one border of its face is an edge piece. */
        private fun edgeSupport(candidate: IntArray, reference: IntArray, n: Int): Int {
            var count = 0
            for (i in candidate.indices) {
                if (candidate[i] == reference[i]) continue
                val within = i % (n * n)
                val row = within / n
                val col = within % n
                val edgeRow = row == 0 || row == n - 1
                val edgeCol = col == 0 || col == n - 1
                if (edgeRow != edgeCol) count++
            }
            return count
        }

        /** Composes two operators into one: apply [a] then [b]. */
        private fun compose(a: Operator, b: Operator, support: Int, edges: Int): Operator =
            Operator(
                a.moves + b.moves,
                IntArray(a.perm.size) { a.perm[b.perm[it]] },
                support,
                edges,
            )

        /** How many of the narrowest operators to compose when refining. */
        private const val REFINE_WIDTH = 800

        /** Ceiling on each refined list. */
        private const val REFINE_CAP = 3000

        /** How many times to compose the narrowest centre operators into narrower ones. */
        private const val REFINE_ROUNDS = 2

        /** How many narrow operators each seeded finisher is paired with. */
        private const val SEED_PARTNERS = 400

        /**
         * Centre operators narrowed down by composition, repeatedly.
         *
         * Enumeration bottoms out at four disturbed centre pieces on a 5x5, and four is one too many
         * at the end of the stage: with three pieces left out of place, any operator touching four
         * gives back what it gains and the score cannot climb. Two operators run back to back can
         * cancel down to something narrower, so this composes the narrowest pairs, keeps whatever
         * came out smaller, and does it again on the result. Two rounds is enough to reach a plain
         * three-cycle; each round doubles the length, so it stops there.
         */
        private fun refineCentre(
            narrow: List<Operator>,
            plan: Layout,
            reference: IntArray,
        ): List<Operator> {
            if (narrow.isEmpty()) return emptyList()
            val collected = ArrayList<Operator>()
            var pool = narrow
            repeat(REFINE_ROUNDS) {
                val floor = pool.first().centreSupport
                if (floor <= 2) return@repeat
                val refined = narrowerPairs(pool, plan, reference, floor - 1)
                if (refined.isEmpty()) return@repeat
                collected.addAll(0, refined)
                pool = refined
            }
            // Keep the coarser operators behind the narrow ones: they still do the bulk of the work
            // early in the stage, where plenty is out of place and precision is not the point.
            collected += narrow.take(REFINE_WIDTH)
            return collected
        }

        /** Composed pairs from [pool] that disturb at most [maxSupport] centre pieces. */
        private fun narrowerPairs(
            pool: List<Operator>,
            plan: Layout,
            reference: IntArray,
            maxSupport: Int,
        ): List<Operator> {
            val width = minOf(REFINE_WIDTH, pool.size)
            val out = ArrayList<Operator>()
            val moved = IntArray(reference.size)
            outer@ for (i in 0 until width) {
                for (j in 0 until width) {
                    if (i == j) continue
                    val a = pool[i]
                    val b = pool[j]
                    for (k in moved.indices) moved[k] = reference[a.perm[b.perm[k]]]
                    val support = plan.centreMismatchCount(moved, reference)
                    if (support in 1..maxSupport) {
                        out += compose(a, b, support, 0)
                        if (out.size >= REFINE_CAP) break@outer
                    }
                }
            }
            out.sortWith(compareBy({ it.centreSupport }, { it.moves.size }))
            return out
        }

        /**
         * Composed pairs that leave one or two edges unpaired on an otherwise finished cube.
         *
         * Run in reverse these are the sequences that finish a cube with one or two edges to go,
         * which is where every straightforward search stalls.
         */
        private fun buildEdgeFinishers(
            centreSafe: List<Operator>,
            plan: Layout,
            reference: IntArray,
            n: Int,
        ): List<Operator> {
            val narrow = centreSafe.take(minOf(REFINE_WIDTH, centreSafe.size))
            val out = ArrayList<Operator>()
            val seeds = ArrayList<Operator>()
            val moved = IntArray(reference.size)

            // Seeded from the long-known pairing algorithms, each kept only if it measurably does
            // what a finisher must: leave every centre block whole and no more than two edges
            // apart. The enumerated families cannot reach these -- they are nine turns long, and
            // enumerating to that depth would take minutes rather than the millisecond a check
            // costs -- but the shape is well known, so checking beats searching.
            for (alg in SEED_FINISHERS) {
                val perm = permutationOf(alg, n)
                for (k in moved.indices) moved[k] = reference[perm[k]]
                if (plan.centreMismatchCount(moved, reference) != 0) continue
                var unpaired = 0
                for (slot in 0 until 12) if (plan.edgeQuality(moved, slot) < 2) unpaired++
                if (unpaired !in 1..2) continue
                val forward = Operator(alg, perm, 0, unpaired)
                val backward = Operator(
                    alg.asReversed().map { it.inverse },
                    invert(perm),
                    0,
                    unpaired,
                )
                out += forward
                out += backward
                seeds += forward
                seeds += backward
            }

            // A seed run against another seed, or against a narrow operator, still leaves the
            // centres whole, and often leaves a *different* pair of edges apart. That matters:
            // which two edges a finisher can rebuild is exactly what decides whether it is the one
            // this position needs, and a handful of fixed algorithms only cover a handful of pairs.
            outer@ for (a in seeds) {
                for (b in seeds + narrow.take(SEED_PARTNERS)) {
                    if (a === b) continue
                    for (k in moved.indices) moved[k] = reference[a.perm[b.perm[k]]]
                    if (plan.centreMismatchCount(moved, reference) != 0) continue
                    var unpaired = 0
                    for (slot in 0 until 12) if (plan.edgeQuality(moved, slot) < 2) unpaired++
                    if (unpaired in 1..2) {
                        out += compose(a, b, 0, unpaired)
                        if (out.size >= REFINE_CAP) break@outer
                    }
                }
            }
            outer@ for (a in narrow) {
                for (b in narrow) {
                    if (a === b) continue
                    for (k in moved.indices) moved[k] = reference[a.perm[b.perm[k]]]
                    if (plan.centreMismatchCount(moved, reference) != 0) continue
                    var unpaired = 0
                    for (slot in 0 until 12) if (plan.edgeQuality(moved, slot) < 2) unpaired++
                    if (unpaired in 1..2) {
                        val op = compose(a, b, 0, unpaired)
                        out += op
                        out += Operator(
                            op.moves.asReversed().map { it.inverse },
                            invert(op.perm),
                            0,
                            unpaired,
                        )
                        if (out.size >= REFINE_CAP) break@outer
                    }
                }
            }
            out.sortBy { it.moves.size }
            return out
        }

        private fun invert(perm: IntArray): IntArray {
            val out = IntArray(perm.size)
            for (i in perm.indices) out[perm[i]] = i
            return out
        }

        /**
         * Two centre-safe operators back to back are centre-safe too, so composing the narrowest
         * ones gives the endgame something a single three-cycle cannot do.
         *
         * The last two edges of a 4x4 need four wings moved in two swaps. That is an even
         * permutation, so no single three-cycle reaches it, and every three-cycle on its own scores
         * worse than doing nothing. A pair of them, treated as one step, lands it in one move.
         */
        fun composePairs(base: List<Operator>, limit: Int): List<Operator> {
            val narrow = base.sortedBy { it.edgeSupport }.take(limit)
            val out = ArrayList<Operator>(narrow.size * narrow.size)
            for (a in narrow) {
                for (b in narrow) {
                    if (a === b) continue
                    val perm = IntArray(a.perm.size) { a.perm[b.perm[it]] }
                    out += Operator(a.moves + b.moves, perm, 0, 0)
                }
            }
            return out
        }

        /** Flattens a sequence into one facelet permutation. */
        fun permutationOf(moves: List<Move>, n: Int): IntArray {
            var perm = IntArray(6 * n * n) { it }
            for (move in moves) {
                val step = MoveEngine.permutation(move, n)
                perm = IntArray(perm.size) { perm[step[it]] }
            }
            return perm
        }

        fun wideMoves(n: Int): List<Move> {
            val out = ArrayList<Move>()
            for (face in com.bigp.rubiksolver.cube.model.FaceId.entries) {
                for (layer in 2..(n + 1) / 2) {
                    for (direction in com.bigp.rubiksolver.cube.model.Direction.entries) {
                        out += Move(face, layer, true, direction)
                    }
                }
            }
            return out
        }

        private fun conjugate(vararg slots: MoveSlot): Shape {
            val mirrors = IntArray(slots.size) { -1 }
            mirrors[slots.size - 1] = 1
            return Shape(slots.toList(), mirrors)
        }

        private fun commutator(setup: MoveSlot, first: MoveSlot, second: MoveSlot): Shape =
            Shape(listOf(setup, first, second, first, second), intArrayOf(-1, -1, -1, 1, 2))
    }
}
