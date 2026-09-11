package com.bigp.rubiksolver.cube.solver.reduction

import com.bigp.rubiksolver.cube.model.CubeSize
import com.bigp.rubiksolver.cube.model.CubeState
import com.bigp.rubiksolver.cube.model.Direction
import com.bigp.rubiksolver.cube.model.Move
import com.bigp.rubiksolver.cube.model.MoveEngine
import com.bigp.rubiksolver.cube.solver.common.CubieCube
import com.bigp.rubiksolver.cube.solver.common.SolveOutcome
import com.bigp.rubiksolver.cube.solver.common.SolveProgress
import com.bigp.rubiksolver.cube.solver.common.Solver
import com.bigp.rubiksolver.cube.solver.twophase.TwoPhaseSolver
import com.bigp.rubiksolver.cube.validation.CubeScheme
import kotlin.random.Random

/**
 * The 4x4 and 5x5 solver, by reduction.
 *
 * Three stages, in order:
 *  1. **Centres** — make every face's centre block one colour.
 *  2. **Edge pairing** — gather each edge's wings (and, on a 5x5, its middle piece) so they read as
 *     one edge, without disturbing the centres.
 *  3. **Solve as a 3x3** — a reduced big cube behaves exactly like a 3x3 under outer turns, so the
 *     position is projected onto a 3x3, handed to the two-phase solver, and replayed as outer turns.
 *
 * ### How each stage decides what to try
 * Two facts about big cubes shape everything here. Outer face turns never carry a centre piece off
 * its own face, so only inner slices and wide turns mix centres; and centre pieces of one colour
 * are interchangeable, so "finished" is a question about colour, not piece identity.
 *
 * Short sequences are searched directly. Beyond that the stages sweep [OperatorPool] — the shaped
 * sequences worth trying, worked out once per cube size — with a free outer turn in front of each.
 * Which sequences are worth trying does not depend on the position, so paying for that once instead
 * of at every one of a few hundred steps is what keeps a solve well under a second.
 *
 * ### Why there is a random walk
 * Both stages hit real plateaus. Near the end of one, every short sequence trades away as much as
 * it gains — the last two edges of a 4x4 genuinely cannot be paired without briefly unpairing
 * something — so a plain hill-climb stalls a few pieces short. Sideways steps chosen at random, a
 * downhill allowance that widens the longer the search goes without beating its own record, and
 * restarts that throw away a wandering attempt rather than keeping it, together get past that and
 * keep the answer short.
 *
 * ### Parity
 * Reduction can end in a position no 3x3 can reach: one edge flipped alone, or two edges swapped.
 * Both are repaired by long-known algorithms, applied here only because `ParityAlgorithmTest` runs
 * them on a solved cube and asserts exactly what they do.
 */
class ReductionSolver(
    private val size: CubeSize,
    seed: Int = 20260907,
    /**
     * Wall-clock ceiling for one solve.
     *
     * Reduction is a stochastic search with restarts, and a rare position can keep it busy for
     * minutes. Giving up after a fixed spell and saying so is better than either: a person waiting
     * on a phone would rather re-check their scan after twenty seconds than watch a spinner.
     */
    private val budgetMillis: Long = 25_000L,
) : Solver {

    init {
        require(size == CubeSize.FOUR || size == CubeSize.FIVE) {
            "ReductionSolver handles 4x4 and 5x5, not ${size.label}"
        }
    }

    private val n = size.n
    private val faceletCount = 6 * n * n
    private val threeByThree = TwoPhaseSolver()
    private val search = StageSearch(n, 5)
    private val random = Random(seed)

    private val outer: List<Move> = MoveEngine.outerMoves()
    private val slices: List<Move> = MoveEngine.innerSliceMoves(n)
    private val carrier: List<Move> = slices + OperatorPool.wideMoves(n)

    /**
     * Conjugating wrappers: an operator is applied as `setup, operator, setup undone`.
     *
     * Conjugating rather than merely prefixing is the whole point. An operator that three-cycles
     * three pieces and leaves everything else alone is the only kind that helps at the end of a
     * stage; putting a turn in front of it and never taking it back throws that away, because the
     * turn moves a whole layer and is never undone. Wrapping the operator instead applies the same
     * three-cycle to a *different* three pieces and still touches nothing else.
     *
     * Which three pieces is exactly what matters. The last two edges of a 4x4 come down to one
     * three-cycle among three named positions, and a single-turn wrapper cannot reach most triples.
     * So there are two tiers: one turn, then -- only if that finds nothing -- two, which between
     * them can carry an operator to essentially any triple on the cube. The second tier is a few
     * hundred times the work, which is why it is never the first thing tried.
     */
    private class Wrapper(val moves: List<Move>, val setup: IntArray?, val undo: IntArray?)

    private fun wrappersOf(depth: Int): List<Wrapper> {
        if (depth == 0) return listOf(Wrapper(emptyList(), null, null))
        // Every move is allowed as a wrapper, slices and wide turns included. Conjugating cannot
        // change how much an operator disturbs -- `x, O, x undone` moves exactly as many pieces as
        // `O` does, just different ones -- so a wrapper that would wreck the centres on its own is
        // perfectly safe here, and leaving those out was throwing away most of the reach. Outer
        // turns only ever shuffle a face's own centre block, so on their own they could never carry
        // a centre operator to another face at all.
        val alphabet = outer + carrier
        val out = ArrayList<Wrapper>()
        for (first in alphabet) {
            if (depth == 1) {
                out += wrapper(listOf(first))
            } else {
                for (second in alphabet) {
                    if (second.sameLayers(first)) continue
                    out += wrapper(listOf(first, second))
                }
            }
        }
        return out
    }

    private fun wrapper(moves: List<Move>): Wrapper = Wrapper(
        moves,
        OperatorPool.permutationOf(moves, n),
        OperatorPool.permutationOf(moves.asReversed().map { it.inverse }, n),
    )

    private val wrappersNone: List<Wrapper> = wrappersOf(0)
    private val wrappersOne: List<Wrapper> = wrappersOf(0) + wrappersOf(1)

    /** Two-turn wrappers, built only if a stage gets close enough to the end to need them. */
    private val wrappersTwo: List<Wrapper> by lazy { wrappersOf(2) }

    private val pool by lazy { OperatorPool.forSize(size) }

    /** Cheap direct searches, tried before the pools. */
    private val shortShapes: List<Shape> = listOf(
        Shape(listOf(outer + slices)),
        Shape(listOf(outer + slices, outer + slices)),
        Shape(listOf(outer + slices, outer + slices, outer + slices)),
    )

    private val scratchA = IntArray(faceletCount)
    private val scratchB = IntArray(faceletCount)
    private val scratchC = IntArray(faceletCount)

    /** Where the last failed stage gave up, for tests and for a useful message. */
    internal var lastStageReport: String = ""
        private set

    /** The position a failed stage gave up on, kept for tests. */
    internal var lastStuckFacelets: IntArray? = null
        private set

    override fun prepare(onProgress: (SolveProgress) -> Unit) {
        threeByThree.prepare(onProgress)
        onProgress(SolveProgress("Preparing big-cube moves", 0.85f))
        pool.centreSafe.size
    }

    override fun solve(state: CubeState, onProgress: (SolveProgress) -> Unit): SolveOutcome {
        require(state.size == size) { "solver built for ${size.label}, got ${state.size.label}" }
        threeByThree.prepare(onProgress)
        val scheme = CubeScheme.resolve(state)
            ?: return SolveOutcome.Failed("These stickers do not describe a real ${size.label}.")
        val plan = Layout(n, scheme)

        var current = state
        val moves = ArrayList<Move>()
        deadline = System.currentTimeMillis() + budgetMillis

        for (attempt in 0..MAX_PASSES) {
            if (outOfTime()) break
            onProgress(SolveProgress("Solving the centres", 0.10f + attempt * 0.04f))
            val centreMoves = solveCentres(current, plan)
                ?: return SolveOutcome.Failed("Could not finish the centres ($lastStageReport).")
            current = current.apply(centreMoves)
            moves += centreMoves

            onProgress(SolveProgress("Pairing the edges", 0.45f + attempt * 0.04f))
            val edgeMoves = pairEdges(current, plan)
            if (edgeMoves == null) {
                // A stage that runs out of ideas is not a dead end. Break reduction on purpose and
                // come back to the endgame from a different position: the centres go back together
                // reliably, so each retry is a fresh, independent attempt at the part that is hard.
                if (attempt >= MAX_PASSES) {
                    return SolveOutcome.Failed("Could not pair up the edges ($lastStageReport).")
                }
                val breaker = perturbation()
                current = current.apply(breaker)
                moves += breaker
                continue
            }
            current = current.apply(edgeMoves)
            moves += edgeMoves

            val projected = plan.projectTo3x3(current)
            val fix = parityFix(projected)
            if (fix != null) {
                onProgress(SolveProgress("Fixing a parity case", 0.72f))
                current = current.apply(fix)
                moves += fix
                continue
            }

            onProgress(SolveProgress("Solving it as a 3x3", 0.82f))
            val outcome = threeByThree.solve(projected)
            if (outcome is SolveOutcome.Solved) {
                current = current.apply(outcome.moves)
                moves += outcome.moves
                if (!current.isSolved) {
                    return SolveOutcome.Failed(
                        "Internal error: the computed move list did not solve the cube."
                    )
                }
                onProgress(SolveProgress("Done", 1f))
                return SolveOutcome.Solved(tidy(moves))
            }
            // Not a parity this solver knows how to name; break reduction and rebuild it elsewhere.
            val breaker = slices[random.nextInt(slices.size)]
            current = current.apply(breaker)
            moves += breaker
        }
        return SolveOutcome.Failed(
            if (outOfTime()) {
                "Gave up on this ${size.label} after ${budgetMillis / 1000} seconds. Check the scan " +
                    "for a misread sticker, then try again."
            } else {
                "Could not finish this ${size.label}. Check the scan for a misread sticker."
            }
        )
    }

    private var deadline: Long = Long.MAX_VALUE

    private fun outOfTime(): Boolean = System.currentTimeMillis() > deadline

    /** A few turns, to land somewhere genuinely different before trying a stage again. */
    private fun perturbation(): List<Move> = listOf(
        carrier[random.nextInt(carrier.size)],
        outer[random.nextInt(outer.size)],
        carrier[random.nextInt(carrier.size)],
    )

    // ---- Stages ----------------------------------------------------------------------------

    private fun solveCentres(state: CubeState, plan: Layout): List<Move>? = runStage(
        label = "centres",
        start = state.toIntArray(),
        target = plan.totalCentreTarget,
        score = { plan.totalCentreScore(it) },
        legal = { true },
        operators = pool.narrowCentre,
        finishers = pool.centreFine,
    )

    private fun pairEdges(state: CubeState, plan: Layout): List<Move>? = runStage(
        label = "edge pairing",
        start = state.toIntArray(),
        target = Layout.EDGE_TARGET,
        score = { plan.edgeQualityScore(it) },
        legal = { plan.allCentresSolved(it) },
        operators = pool.centreSafe,
        finishers = pool.edgeFinishers,
        deep = true,
    )

    private fun runStage(
        label: String,
        start: IntArray,
        target: Int,
        score: (IntArray) -> Int,
        legal: (IntArray) -> Boolean,
        operators: List<Operator>,
        finishers: List<Operator>,
        deep: Boolean = false,
    ): List<Move>? {
        var best = score(start)
        for (attempt in 0 until STAGE_ATTEMPTS) {
            if (outOfTime()) break
            val result = attemptStage(start, target, score, legal, operators, finishers, deep)
            if (result != null) return result.first
            best = maxOf(best, lastAttemptBest)
        }
        lastStageReport = "$label reached $best of $target after $STAGE_ATTEMPTS attempts"
        return null
    }

    private var lastAttemptBest = 0

    private fun attemptStage(
        start: IntArray,
        target: Int,
        score: (IntArray) -> Int,
        legal: (IntArray) -> Boolean,
        operators: List<Operator>,
        finishers: List<Operator>,
        deep: Boolean,
    ): Pair<List<Move>, Int>? {
        var facelets = start.copyOf()
        val out = ArrayList<Move>()
        var best = score(facelets)
        lastAttemptBest = best
        var stalls = 0
        while (true) {
            val before = score(facelets)
            if (before >= target) return out to before

            var step = findShort(facelets) { legal(it) && score(it) > before }
                ?: findOperator(facelets, operators, wrappersOne) {
                    legal(it) && score(it) > before
                }

            val nearlyDone = before >= target - DEEP_WINDOW
            if (step == null && nearlyDone) {
                // Only the endgame pays for the refined operators and the wider wrappers.
                step = findOperator(facelets, finishers, wrappersOne) {
                    legal(it) && score(it) > before
                } ?: findOperator(facelets, narrowSlice(operators), wrappersTwo) {
                    legal(it) && score(it) > before
                } ?: findOperator(facelets, narrowSlice(finishers), wrappersTwo) {
                    legal(it) && score(it) > before
                }
            }

            if (step == null) {
                stalls++
                // Give up more ground the longer this goes without beating its own record.
                val allowance = if (stalls < 12) 0 else if (stalls < 40) 1 else 2
                step = bestSideways(facelets, operators, wrappersOne, before, allowance, legal, score)
                    ?: bestSideways(facelets, operators, wrappersNone, before, allowance, legal, score)
                    ?: run {
                        lastAttemptBest = best
                        lastStuckFacelets = facelets.copyOf()
                        return null
                    }
            }
            for (m in step) facelets = applyTo(facelets, m)
            out += step
            val after = score(facelets)
            if (after > best) {
                best = after
                lastAttemptBest = best
                stalls = 0
            }
            if (stalls > MAX_STALLS || out.size > MAX_STAGE_MOVES || outOfTime()) {
                lastAttemptBest = best
                lastStuckFacelets = facelets.copyOf()
                return null
            }
        }
    }

    // ---- Candidate generation ---------------------------------------------------------------

    private fun findShort(facelets: IntArray, accept: (IntArray) -> Boolean): List<Move>? {
        for (shape in shortShapes) {
            val found = search.find(facelets, shape, accept)
            if (found != null) return found
        }
        return null
    }

    /** Sweeps every wrapped operator, narrowest first, and returns the first that fits. */
    private fun findOperator(
        facelets: IntArray,
        operators: List<Operator>,
        wrappers: List<Wrapper>,
        accept: (IntArray) -> Boolean,
    ): List<Move>? {
        for (wrap in wrappers) {
            val staged = stage(facelets, wrap.setup)
            for (op in operators) {
                val result = finish(staged, op.perm, wrap.undo)
                if (accept(result)) return buildSequence(wrap, op)
            }
        }
        return null
    }

    /**
     * The best sideways step available, chosen at random among the ties.
     *
     * Taking the *best* allowed step rather than any allowed step matters. A stalled stage has
     * thousands of legal moves that merely hold the score, and wandering uniformly among them
     * rarely arrives anywhere the next improvement is one operator away. Ties are still broken at
     * random, which is what stops a restart from walking the same path twice.
     */
    private fun bestSideways(
        facelets: IntArray,
        operators: List<Operator>,
        wrappers: List<Wrapper>,
        before: Int,
        allowance: Int,
        legal: (IntArray) -> Boolean,
        score: (IntArray) -> Int,
    ): List<Move>? {
        var bestScore = Int.MIN_VALUE
        var picked: List<Move>? = null
        var ties = 0
        val floor = before - allowance
        for (wrap in wrappers) {
            val staged = stage(facelets, wrap.setup)
            for (op in operators) {
                val result = finish(staged, op.perm, wrap.undo)
                if (!legal(result)) continue
                val value = score(result)
                if (value < floor) continue
                if (value > bestScore) {
                    bestScore = value
                    ties = 1
                    picked = buildSequence(wrap, op)
                } else if (value == bestScore) {
                    ties++
                    if (random.nextInt(ties) == 0) picked = buildSequence(wrap, op)
                }
            }
        }
        return picked
    }

    private fun buildSequence(wrap: Wrapper, op: Operator): List<Move> =
        if (wrap.moves.isEmpty()) {
            op.moves
        } else {
            wrap.moves + op.moves + wrap.moves.asReversed().map { it.inverse }
        }

    /** Applies the operator to the staged position, then undoes the setup. */
    private fun finish(staged: IntArray, opPerm: IntArray, undoPerm: IntArray?): IntArray {
        applyPerm(staged, opPerm, scratchB)
        if (undoPerm == null) return scratchB
        applyPerm(scratchB, undoPerm, scratchC)
        return scratchC
    }

    private fun stage(facelets: IntArray, setupPerm: IntArray?): IntArray {
        if (setupPerm == null) return facelets
        applyPerm(facelets, setupPerm, scratchA)
        return scratchA
    }

    private fun applyPerm(source: IntArray, perm: IntArray, target: IntArray) {
        for (i in target.indices) target[i] = source[perm[i]]
    }

    /** The narrowest slice of a pool, for the tiers that cannot afford to sweep all of it. */
    private fun narrowSlice(operators: List<Operator>): List<Operator> =
        if (operators.size <= NARROW_SLICE) operators else operators.subList(0, NARROW_SLICE)

    private fun applyTo(facelets: IntArray, move: Move): IntArray {
        val perm = MoveEngine.permutation(move, n)
        return IntArray(facelets.size) { facelets[perm[it]] }
    }

    // ---- Parity ------------------------------------------------------------------------------

    /**
     * The algorithm needed to drag [projected] into the set of positions a 3x3 can reach, or null
     * when it is already there.
     *
     * On a 4x4 both fixes leave the cube reduced, so the next pass goes straight to the 3x3 solve.
     * On a 5x5 they unpair one edge, which the next pass's edge stage puts back.
     */
    private fun parityFix(projected: CubeState): List<Move>? {
        val cube = try {
            CubieCube.fromState(projected)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (cube.isReachable) return null
        if (cube.cp.toSortedSet().size != 8 || cube.ep.toSortedSet().size != 12) return null
        if (cube.eo.sum() % 2 != 0) return EDGE_FLIP_PARITY
        if (CubieCube.permutationParity(cube.cp) != CubieCube.permutationParity(cube.ep)) {
            return EDGE_SWAP_PARITY
        }
        return null
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /** Runs stages one and two only, for tests that need to look at the reduced position. */
    internal fun reduceOnly(state: CubeState): Pair<CubeState, Layout>? {
        threeByThree.prepare()
        val scheme = CubeScheme.resolve(state) ?: return null
        val plan = Layout(n, scheme)
        val centreMoves = solveCentres(state, plan) ?: return null
        val afterCentres = state.apply(centreMoves)
        val edgeMoves = pairEdges(afterCentres, plan) ?: return null
        return afterCentres.apply(edgeMoves) to plan
    }

    /** Merges neighbouring turns of the same layer and drops any that cancel out. */
    private fun tidy(moves: List<Move>): List<Move> {
        val out = ArrayList<Move>(moves.size)
        for (move in moves) {
            val last = out.lastOrNull()
            if (last != null && last.sameLayers(move)) {
                val quarters = (last.direction.quarterTurns + move.direction.quarterTurns) % 4
                out.removeAt(out.size - 1)
                if (quarters != 0) out += last.copy(direction = Direction.fromQuarterTurns(quarters))
            } else {
                out += move
            }
        }
        return out
    }

    companion object {
        private const val MAX_STAGE_MOVES = 320
        private const val STAGE_ATTEMPTS = 3
        private const val MAX_STALLS = 70
        private const val MAX_PASSES = 5

        /** How close to finished a stage must be before it reaches for the expensive tiers. */
        private const val DEEP_WINDOW = 8

        /** How many operators the two-turn-wrapper tier sweeps. */
        private const val NARROW_SLICE = 260

        /**
         * Flips one edge in place, leaving the centres finished and every edge still paired. The
         * long-known algorithm, used here only because `ParityAlgorithmTest` asserts what it does.
         */
        val EDGE_FLIP_PARITY: List<Move> =
            Move.parseAlgorithm("2R2 B2 U2 2L U2 2R' U2 2R U2 F2 2R F2 2L' B2 2R2")

        /** Swaps two edges, leaving the centres finished and every edge still paired. */
        val EDGE_SWAP_PARITY: List<Move> = Move.parseAlgorithm("2R2 U2 2R2 Uw2 2R2 Uw2")
    }
}
