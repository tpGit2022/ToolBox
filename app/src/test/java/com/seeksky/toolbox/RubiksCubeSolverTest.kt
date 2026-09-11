package com.seeksky.toolbox

import com.bigp.rubiksolver.cube.model.CubeSize
import com.bigp.rubiksolver.cube.model.CubeState
import com.bigp.rubiksolver.cube.model.Move
import com.bigp.rubiksolver.cube.solver.SolverFactory
import com.bigp.rubiksolver.cube.solver.common.SolveOutcome
import com.bigp.rubiksolver.cube.validation.CubeValidator
import com.bigp.rubiksolver.cube.validation.ValidationResult
import org.junit.Assert.assertTrue
import org.junit.Test

class RubiksCubeSolverTest {

    @Test
    fun moveAndInverse_restoreEverySupportedSize() {
        val move = Move.parse("R")!!
        for (size in listOf(CubeSize.THREE, CubeSize.FOUR, CubeSize.FIVE)) {
            val solved = CubeState.solved(size)
            assertTrue(solved.apply(listOf(move, move.inverse)).isSolved)
        }
    }

    @Test
    fun solvedStates_validateAndNeedNoMoves() {
        for (size in listOf(CubeSize.THREE, CubeSize.FOUR, CubeSize.FIVE)) {
            val state = CubeState.solved(size)
            assertTrue(CubeValidator.validate(state) is ValidationResult.Valid)
            val outcome = SolverFactory.forSize(size).solve(state)
            assertTrue(outcome is SolveOutcome.Solved && outcome.moves.isEmpty())
        }
    }

    @Test
    fun returnedThreeByThreeSolution_replaysToSolved() {
        val scramble = Move.parseAlgorithm("R U R' U' F2 D L2")
        val scrambled = CubeState.solved(CubeSize.THREE).apply(scramble)
        val solver = SolverFactory.forSize(CubeSize.THREE)
        solver.prepare()
        val outcome = solver.solve(scrambled)
        assertTrue(outcome is SolveOutcome.Solved)
        assertTrue(scrambled.apply((outcome as SolveOutcome.Solved).moves).isSolved)
    }

    @Test
    fun reducedLargeCubeSolution_replaysToSolved() {
        // Outer turns preserve paired centres/edges, exercising the large-cube projection and
        // two-phase hand-off deterministically without depending on the stochastic pairing stage.
        val scramble = Move.parseAlgorithm("R U2 F' L D B2 R'")
        for (size in listOf(CubeSize.FOUR, CubeSize.FIVE)) {
            val scrambled = CubeState.solved(size).apply(scramble)
            val solver = SolverFactory.forSize(size)
            solver.prepare()
            val outcome = solver.solve(scrambled)
            assertTrue(outcome is SolveOutcome.Solved)
            assertTrue(scrambled.apply((outcome as SolveOutcome.Solved).moves).isSolved)
        }
    }
}
