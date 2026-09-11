package com.bigp.rubiksolver.data

import android.content.Context
import com.bigp.rubiksolver.cube.model.CubeSize
import com.bigp.rubiksolver.cube.model.CubeState
import com.bigp.rubiksolver.cube.solver.SolverFactory
import com.bigp.rubiksolver.cube.solver.common.SolveOutcome
import com.bigp.rubiksolver.cube.solver.common.SolveProgress
import com.bigp.rubiksolver.cube.solver.twophase.TwoPhaseTables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs solvers off the main thread and keeps the expensive tables on disk between launches.
 *
 * The 3x3 solver needs about 8 MB of pruning tables. Generating them takes roughly a second, which
 * is fine once and irritating every time, so the first run writes them to internal storage and
 * every run after reads them back. Nothing is bundled in the APK: a few seconds on first use is a
 * better trade than several megabytes of binary blobs in the repository, and it keeps the build
 * reproducible from source alone.
 */
class SolverService(private val context: Context) {

    private val cacheFile: File get() = File(context.filesDir, TABLE_FILE)

    /** Loads or builds the solver tables. Safe to call repeatedly. */
    suspend fun warmUp(onProgress: (SolveProgress) -> Unit = {}) = withContext(Dispatchers.Default) {
        if (TwoPhaseTables.isBuilt) return@withContext
        val file = cacheFile
        if (file.exists()) {
            val loaded = runCatching {
                file.inputStream().use { TwoPhaseTables.readFrom(it, onProgress) }
            }.getOrDefault(false)
            if (loaded) return@withContext
            file.delete()
        }
        TwoPhaseTables.ensureBuilt(onProgress)
        runCatching {
            val temp = File(file.parentFile, "$TABLE_FILE.tmp")
            temp.outputStream().use { TwoPhaseTables.writeTo(it) }
            if (!temp.renameTo(file)) temp.delete()
        }
    }

    /** Solves [state], reporting progress as it goes. */
    suspend fun solve(
        state: CubeState,
        onProgress: (SolveProgress) -> Unit = {},
    ): SolveOutcome = withContext(Dispatchers.Default) {
        warmUp(onProgress)
        val solver = SolverFactory.forSize(state.size)
        solver.prepare(onProgress)
        solver.solve(state, onProgress)
    }

    /** True when a solve is likely to take long enough to be worth warning about. */
    fun isSlow(size: CubeSize): Boolean = size == CubeSize.FOUR || size == CubeSize.FIVE

    companion object {
        private const val TABLE_FILE = "two-phase-tables.bin"
    }
}
