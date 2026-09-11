package com.bigp.rubiksolver.cube.solver.common

/**
 * Index encodings for permutations and subsets, used to turn cube states into flat array offsets.
 *
 * Both encodings are bijections onto a contiguous range, which the unit tests assert exhaustively.
 */
object Combinatorics {

    private val binomial: Array<IntArray> = Array(25) { n ->
        IntArray(25) { k ->
            when {
                k > n -> 0
                k == 0 || k == n -> 1
                else -> 0
            }
        }
    }.also { table ->
        for (n in 1 until 25) for (k in 1 until n) {
            table[n][k] = table[n - 1][k - 1] + table[n - 1][k]
        }
    }

    fun choose(n: Int, k: Int): Int =
        if (k < 0 || n < 0 || k > n || n >= binomial.size) 0 else binomial[n][k]

    private val factorial = IntArray(13).also {
        it[0] = 1
        for (i in 1 until 13) it[i] = it[i - 1] * i
    }

    fun factorial(n: Int): Int = factorial[n]

    /**
     * Lexicographic rank of [perm], a permutation of `0 until perm.size`. Range `0 until size!`.
     */
    fun permutationIndex(perm: IntArray): Int {
        var index = 0
        for (i in perm.indices) {
            var smaller = 0
            for (j in i + 1 until perm.size) if (perm[j] < perm[i]) smaller++
            index += smaller * factorial[perm.size - 1 - i]
        }
        return index
    }

    /** Inverse of [permutationIndex]. */
    fun permutationFromIndex(index: Int, size: Int): IntArray {
        var rest = index
        val available = MutableList(size) { it }
        val out = IntArray(size)
        for (i in 0 until size) {
            val f = factorial[size - 1 - i]
            val pick = rest / f
            rest %= f
            out[i] = available.removeAt(pick)
        }
        return out
    }

    /**
     * Rank of a sorted [combination] of `k` values drawn from `0 until n`, in the combinatorial
     * number system. Range `0 until choose(n, k)`.
     */
    fun combinationIndex(combination: IntArray): Int {
        var index = 0
        for (i in combination.indices) index += choose(combination[i], i + 1)
        return index
    }

    /** Inverse of [combinationIndex]: the sorted `k`-subset of `0 until n` with rank [index]. */
    fun combinationFromIndex(index: Int, n: Int, k: Int): IntArray {
        var rest = index
        val out = IntArray(k)
        for (i in k downTo 1) {
            var c = i - 1
            while (c + 1 < n && choose(c + 1, i) <= rest) c++
            out[i - 1] = c
            rest -= choose(c, i)
        }
        return out
    }
}
