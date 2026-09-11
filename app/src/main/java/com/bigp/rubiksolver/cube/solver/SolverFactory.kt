package com.bigp.rubiksolver.cube.solver

import com.bigp.rubiksolver.cube.model.CubeSize
import com.bigp.rubiksolver.cube.solver.common.Solver
import com.bigp.rubiksolver.cube.solver.reduction.ReductionSolver
import com.bigp.rubiksolver.cube.solver.twobytwo.TwoByTwoSolver
import com.bigp.rubiksolver.cube.solver.twophase.TwoPhaseSolver

/** Picks the right solver for a cube size. */
object SolverFactory {
    fun forSize(size: CubeSize): Solver = when (size) {
        CubeSize.TWO -> TwoByTwoSolver()
        CubeSize.THREE -> TwoPhaseSolver()
        CubeSize.FOUR, CubeSize.FIVE -> ReductionSolver(size)
    }
}
