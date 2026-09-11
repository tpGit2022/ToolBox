package com.bigp.rubiksolver.cube.validation

import com.bigp.rubiksolver.cube.model.CubeSize
import com.bigp.rubiksolver.cube.model.CubeState
import com.bigp.rubiksolver.cube.model.FaceId
import com.bigp.rubiksolver.cube.model.FaceletColor
import com.bigp.rubiksolver.cube.model.Geometry

/** One thing wrong with a scanned cube, with enough detail to point the user at it. */
data class ValidationIssue(
    val message: String,
    /** Flat facelet indices worth highlighting on the correction net. Possibly empty. */
    val facelets: List<Int> = emptyList(),
)

sealed interface ValidationResult {
    data class Valid(val scheme: CubeScheme) : ValidationResult
    data class Invalid(val issues: List<ValidationIssue>) : ValidationResult
}

/**
 * Checks that a scanned cube could physically exist before any solver touches it.
 *
 * Which parity laws apply depends on the size:
 *  - **2x2, 4x4 and 5x5** only constrain corner twist. Their wing and centre pieces are
 *    interchangeable by colour, so they absorb every permutation parity; a lone flipped edge or a
 *    swapped pair on a big cube is a legal state that just needs a parity algorithm.
 *  - **3x3** additionally constrains edge flip, and ties corner permutation parity to edge
 *    permutation parity.
 */
object CubeValidator {

    fun validate(state: CubeState): ValidationResult {
        val issues = ArrayList<ValidationIssue>()

        val counts = countIssues(state)
        if (counts.isNotEmpty()) return ValidationResult.Invalid(counts)

        if (state.n % 2 == 1) {
            val centres = centreIssues(state)
            if (centres.isNotEmpty()) return ValidationResult.Invalid(centres)
        }

        val scheme = CubeScheme.resolve(state)
        if (scheme == null) {
            return ValidationResult.Invalid(
                listOf(
                    ValidationIssue(
                        "These stickers cannot come from a real cube: two colours that belong on " +
                            "opposite faces are sharing a corner. Check the corner pieces."
                    )
                )
            )
        }

        val corners = CornerAnalysis.of(state, scheme)
        if (corners == null) {
            return ValidationResult.Invalid(
                listOf(
                    ValidationIssue(
                        "One of the corner pieces shows a colour combination that does not exist " +
                            "on this cube. Re-check the eight corners.",
                        cornerFaceletList(state.n),
                    )
                )
            )
        }

        if (corners.permutation.toSortedSet().size != 8) {
            issues += ValidationIssue(
                "Two corners are showing the same three colours. Every corner piece is unique, so " +
                    "at least one of them is misread.",
                cornerFaceletList(state.n),
            )
        }
        if (corners.orientationSum % 3 != 0) {
            issues += ValidationIssue(
                "A corner is twisted in a way a real cube cannot be. Check the corner stickers: " +
                    "the total twist has to come out to a multiple of three.",
                cornerFaceletList(state.n),
            )
        }

        if (state.size == CubeSize.THREE) {
            val edges = EdgeAnalysis.of(state, scheme)
            if (edges == null) {
                issues += ValidationIssue(
                    "One of the edge pieces shows a colour pair that does not exist on this cube.",
                    edgeFaceletList(state.n),
                )
            } else {
                if (edges.permutation.toSortedSet().size != 12) {
                    issues += ValidationIssue(
                        "Two edges are showing the same colour pair. Every edge piece is unique.",
                        edgeFaceletList(state.n),
                    )
                }
                if (edges.orientationSum % 2 != 0) {
                    issues += ValidationIssue(
                        "A single edge is flipped, which a 3x3 cannot do. One edge piece has its " +
                            "two stickers the wrong way round.",
                        edgeFaceletList(state.n),
                    )
                }
                if (edges.permutation.toSortedSet().size == 12 &&
                    corners.permutation.toSortedSet().size == 8 &&
                    parityOf(corners.permutation) != parityOf(edges.permutation)
                ) {
                    issues += ValidationIssue(
                        "Two pieces look swapped in a way a 3x3 cannot reach. Two stickers are " +
                            "most likely transposed."
                    )
                }
            }
        }

        if (state.n >= 4) issues += wingIssues(state, scheme)

        return if (issues.isEmpty()) {
            ValidationResult.Valid(scheme)
        } else {
            ValidationResult.Invalid(issues)
        }
    }

    private fun countIssues(state: CubeState): List<ValidationIssue> {
        val per = state.n * state.n
        val counts = IntArray(6)
        for (i in 0 until state.size.totalFacelets) counts[state.ordinalAt(i)]++
        val wrong = FaceletColor.ALL.filter { counts[it.ordinal] != per }
        if (wrong.isEmpty()) return emptyList()
        val detail = wrong.joinToString(", ") {
            it.displayName.lowercase() + " " + counts[it.ordinal]
        }
        return listOf(
            ValidationIssue(
                "Every colour needs exactly $per stickers, but the scan found $detail. Tap the " +
                    "wrong squares on the net to fix them.",
                (0 until state.size.totalFacelets).filter {
                    FaceletColor.ALL[state.ordinalAt(it)] in wrong
                },
            )
        )
    }

    private fun centreIssues(state: CubeState): List<ValidationIssue> {
        val mid = state.n / 2
        val centres = FaceId.entries.map { state.colorAt(it, mid, mid) }
        if (centres.toSet().size == 6) return emptyList()
        val dupes = centres.groupBy { it }.filterValues { it.size > 1 }.keys
        return listOf(
            ValidationIssue(
                "Two faces have the same centre colour (" +
                    dupes.joinToString { it.displayName } +
                    "). On a ${state.size.label} the centres never move, so each one must differ.",
                FaceId.entries.map { state.index(it, mid, mid) },
            )
        )
    }

    private fun wingIssues(state: CubeState, scheme: CubeScheme): List<ValidationIssue> {
        val n = state.n
        val expected = HashMap<Set<FaceletColor>, Int>()
        for (slot in Geometry.EDGE_SLOTS.indices) {
            val pairFaces = Geometry.EDGE_SLOTS[slot]
            val key = setOf(
                scheme.colorOf.getValue(pairFaces.first),
                scheme.colorOf.getValue(pairFaces.second),
            )
            expected[key] = (expected[key] ?: 0) + (n - 2)
        }
        val seen = HashMap<Set<FaceletColor>, Int>()
        val bad = ArrayList<Int>()
        for (slot in Geometry.EDGE_SLOTS.indices) {
            for (pair in Geometry.edgeStripFacelets(slot, n)) {
                val key = setOf(state.colorAt(pair[0]), state.colorAt(pair[1]))
                if (key.size != 2 || expected[key] == null) {
                    bad += pair[0]
                    bad += pair[1]
                } else {
                    seen[key] = (seen[key] ?: 0) + 1
                }
            }
        }
        if (bad.isNotEmpty()) {
            return listOf(
                ValidationIssue(
                    "Some edge pieces show colour pairs this cube does not have. Check the edges.",
                    bad,
                )
            )
        }
        if (seen != expected) {
            return listOf(
                ValidationIssue(
                    "The edge pieces do not add up: at least one colour pair appears the wrong " +
                        "number of times."
                )
            )
        }
        return emptyList()
    }

    private fun cornerFaceletList(n: Int): List<Int> =
        Geometry.CORNER_SLOTS.indices.flatMap { Geometry.cornerFacelets(it, n).toList() }

    private fun edgeFaceletList(n: Int): List<Int> =
        Geometry.EDGE_SLOTS.indices.flatMap { Geometry.edgeFacelets(it, n).toList() }

    /** 0 for an even permutation, 1 for an odd one. */
    fun parityOf(perm: IntArray): Int {
        var swaps = 0
        val a = perm.copyOf()
        for (i in a.indices) {
            while (a[i] != i) {
                val j = a[i]
                val t = a[j]
                a[j] = a[i]
                a[i] = t
                swaps++
            }
        }
        return swaps % 2
    }
}

/** Corner permutation and twist, read off the stickers. */
class CornerAnalysis(val permutation: IntArray, val orientation: IntArray) {
    val orientationSum: Int get() = orientation.sum()

    companion object {
        /**
         * Identifies which corner cubie sits in each slot and how far it is twisted, or null when a
         * corner shows a colour triple the cube does not have. Matching is done on the *clockwise
         * cyclic order* of the three stickers rather than on the unordered set, so this also
         * rejects a mirrored, physically impossible colour scheme.
         */
        fun of(state: CubeState, scheme: CubeScheme): CornerAnalysis? {
            val n = state.n
            val reference = Geometry.CORNER_SLOTS.map { slot ->
                intArrayOf(
                    scheme.colorOf.getValue(slot.first).ordinal,
                    scheme.colorOf.getValue(slot.second).ordinal,
                    scheme.colorOf.getValue(slot.third).ordinal,
                )
            }
            val perm = IntArray(8)
            val ori = IntArray(8)
            for (slot in 0 until 8) {
                val f = Geometry.cornerFacelets(slot, n)
                val observed = IntArray(3) { state.ordinalAt(f[it]) }
                var found = false
                outer@ for (cubie in 0 until 8) {
                    val ref = reference[cubie]
                    for (twist in 0 until 3) {
                        if (observed[0] == ref[twist] &&
                            observed[1] == ref[(twist + 1) % 3] &&
                            observed[2] == ref[(twist + 2) % 3]
                        ) {
                            perm[slot] = cubie
                            ori[slot] = twist
                            found = true
                            break@outer
                        }
                    }
                }
                if (!found) return null
            }
            return CornerAnalysis(perm, ori)
        }
    }
}

/** Middle-edge permutation and flip, read off the stickers. Odd cubes only. */
class EdgeAnalysis(val permutation: IntArray, val orientation: IntArray) {
    val orientationSum: Int get() = orientation.sum()

    companion object {
        fun of(state: CubeState, scheme: CubeScheme): EdgeAnalysis? {
            if (state.n % 2 == 0) return null
            val n = state.n
            val reference = Geometry.EDGE_SLOTS.map { slot ->
                intArrayOf(
                    scheme.colorOf.getValue(slot.first).ordinal,
                    scheme.colorOf.getValue(slot.second).ordinal,
                )
            }
            val perm = IntArray(12)
            val ori = IntArray(12)
            for (slot in 0 until 12) {
                val f = Geometry.edgeFacelets(slot, n)
                val observed = intArrayOf(state.ordinalAt(f[0]), state.ordinalAt(f[1]))
                var found = false
                outer@ for (cubie in 0 until 12) {
                    val ref = reference[cubie]
                    if (observed[0] == ref[0] && observed[1] == ref[1]) {
                        perm[slot] = cubie
                        ori[slot] = 0
                        found = true
                        break@outer
                    }
                    if (observed[0] == ref[1] && observed[1] == ref[0]) {
                        perm[slot] = cubie
                        ori[slot] = 1
                        found = true
                        break@outer
                    }
                }
                if (!found) return null
            }
            return EdgeAnalysis(perm, ori)
        }
    }
}
