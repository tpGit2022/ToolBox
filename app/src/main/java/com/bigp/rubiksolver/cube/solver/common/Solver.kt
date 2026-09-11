package com.bigp.rubiksolver.cube.solver.common

import com.bigp.rubiksolver.cube.model.CubeState
import com.bigp.rubiksolver.cube.model.Move

/** Coarse progress while a solve runs, so the UI can say something better than "please wait". */
data class SolveProgress(val stage: String, val fraction: Float)

/** What a solver hands back. */
sealed interface SolveOutcome {
    data class Solved(val moves: List<Move>) : SolveOutcome
    data class Failed(val reason: String) : SolveOutcome
}

/**
 * A solver for one cube size.
 *
 * Implementations must be pure logic with no Android dependencies so they can be unit tested on the
 * JVM, which is where the correctness gate for this app lives.
 */
interface Solver {
    /** Table generation, if any. Safe to call more than once; later calls are cheap. */
    fun prepare(onProgress: (SolveProgress) -> Unit = {})

    /**
     * Solves [state], which the caller must already have validated.
     *
     * @param onProgress called occasionally; never on a guaranteed schedule.
     */
    fun solve(state: CubeState, onProgress: (SolveProgress) -> Unit = {}): SolveOutcome
}
