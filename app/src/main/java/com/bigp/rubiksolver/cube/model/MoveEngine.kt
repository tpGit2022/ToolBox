package com.bigp.rubiksolver.cube.model

/**
 * Turns [Move]s into facelet permutations, for any cube size.
 *
 * Nothing here is hand-tabulated. Every permutation is derived from one 3D rotation matrix per
 * face, so the six basic turns cannot drift out of sync with each other and the whole engine is
 * verifiable by inspection of eight lines of arithmetic.
 *
 * ## Coordinate system
 * Right-handed: `x` grows to the right, `y` grows up, `z` grows toward the viewer. A size-`n` cube
 * fills the integer box `[0, n-1]^3`. Faces sit at `U:y=n-1`, `D:y=0`, `R:x=n-1`, `L:x=0`,
 * `F:z=n-1`, `B:z=0`.
 *
 * ## Facelet indexing
 * Facelet `(face, row, col)` has flat index `face.ordinal * n * n + row * n + col`, with rows and
 * columns read the way you would read that face if you rotated the cube to look straight at it,
 * keeping U up (and keeping F up when looking at U or D). That is the standard net layout, so
 * `U[0][0]` is the ULB corner sticker, `F[0][0]` is the UFL corner sticker, and so on.
 */
object MoveEngine {

    /** Maps `(face, row, col)` to the 3D cubie coordinate carrying that sticker. */
    fun coordOf(face: FaceId, row: Int, col: Int, n: Int): IntArray {
        val m = n - 1
        return when (face) {
            FaceId.U -> intArrayOf(col, m, row)
            FaceId.D -> intArrayOf(col, 0, m - row)
            FaceId.F -> intArrayOf(col, m - row, m)
            FaceId.B -> intArrayOf(m - col, m - row, 0)
            FaceId.R -> intArrayOf(m, m - row, m - col)
            FaceId.L -> intArrayOf(0, m - row, col)
        }
    }

    /** Outward unit normal of [face], as `(x, y, z)`. */
    fun normalOf(face: FaceId): IntArray = when (face) {
        FaceId.U -> intArrayOf(0, 1, 0)
        FaceId.D -> intArrayOf(0, -1, 0)
        FaceId.R -> intArrayOf(1, 0, 0)
        FaceId.L -> intArrayOf(-1, 0, 0)
        FaceId.F -> intArrayOf(0, 0, 1)
        FaceId.B -> intArrayOf(0, 0, -1)
    }

    /**
     * One clockwise quarter turn about [face]'s axis, applied to a vector in *centred* coordinates
     * (origin at the cube's centre). Viewed from outside [face] the turn goes clockwise.
     *
     * Each line is read off the screen basis of that face: clockwise sends screen-up to
     * screen-right, so for U (screen-right `+x`, screen-up `-z`) we get `(u,v,w) -> (-w, v, u)`.
     */
    private fun rotate(face: FaceId, u: Int, v: Int, w: Int): IntArray = when (face) {
        FaceId.U -> intArrayOf(-w, v, u)
        FaceId.R -> intArrayOf(u, w, -v)
        FaceId.F -> intArrayOf(v, -u, w)
        FaceId.D -> intArrayOf(w, v, -u)
        FaceId.L -> intArrayOf(u, -w, v)
        FaceId.B -> intArrayOf(-v, u, w)
    }

    /** Coordinate along [face]'s axis for 0-based depth [depth] (0 = the face's own layer). */
    private fun layerCoordinate(face: FaceId, depth: Int, n: Int): Int = when (face) {
        FaceId.U, FaceId.R, FaceId.F -> n - 1 - depth
        FaceId.D, FaceId.L, FaceId.B -> depth
    }

    /** Index into `(x, y, z)` of the axis [face] lies on. */
    private fun axisIndex(face: FaceId): Int = when (face) {
        FaceId.R, FaceId.L -> 0
        FaceId.U, FaceId.D -> 1
        FaceId.F, FaceId.B -> 2
    }

    private data class Key(val n: Int, val move: Move)

    private val cache = HashMap<Key, IntArray>()

    /**
     * Permutation array `p` for [move] on a size-[n] cube such that
     * `next[i] = current[p[i]]` for every facelet index `i`.
     */
    @Synchronized
    fun permutation(move: Move, n: Int): IntArray = cache.getOrPut(Key(n, move)) { build(move, n) }

    private fun build(move: Move, n: Int): IntArray {
        val total = 6 * n * n
        // Forward map: where does the sticker at index i end up?
        val forward = IntArray(total) { it }
        val axis = axisIndex(move.face)
        val liveCoords = move.depths.map { layerCoordinate(move.face, it, n) }.toHashSet()
        // Reverse lookup from (coordinate, outward normal) back to a facelet index.
        val lookup = HashMap<Long, Int>(total * 2)
        for (face in FaceId.entries) {
            val nrm = normalOf(face)
            for (row in 0 until n) for (col in 0 until n) {
                val c = coordOf(face, row, col, n)
                lookup[encode(c, nrm, n)] = face.ordinal * n * n + row * n + col
            }
        }
        val doubled = n - 1 // centred coordinates are stored doubled to stay integral
        for (face in FaceId.entries) {
            val nrm = normalOf(face)
            for (row in 0 until n) for (col in 0 until n) {
                val src = face.ordinal * n * n + row * n + col
                val c = coordOf(face, row, col, n)
                if (c[axis] !in liveCoords) continue
                // Centre, doubling so the (n even) half-integer centre stays an integer.
                var cu = 2 * c[0] - doubled
                var cv = 2 * c[1] - doubled
                var cw = 2 * c[2] - doubled
                var nu = nrm[0]
                var nv = nrm[1]
                var nw = nrm[2]
                repeat(move.direction.quarterTurns) {
                    val p = rotate(move.face, cu, cv, cw)
                    cu = p[0]; cv = p[1]; cw = p[2]
                    val q = rotate(move.face, nu, nv, nw)
                    nu = q[0]; nv = q[1]; nw = q[2]
                }
                val dst = intArrayOf((cu + doubled) / 2, (cv + doubled) / 2, (cw + doubled) / 2)
                val key = encode(dst, intArrayOf(nu, nv, nw), n)
                forward[src] = lookup[key]
                    ?: error("Rotation left the cube surface for $move on size $n")
            }
        }
        // Invert: perm[dest] = src.
        val perm = IntArray(total)
        for (i in 0 until total) perm[forward[i]] = i
        return perm
    }

    private fun encode(coord: IntArray, normal: IntArray, n: Int): Long {
        var k = 0L
        for (v in coord) k = k * (n + 1) + v
        for (v in normal) k = k * 3 + (v + 1)
        return k
    }

    /** Every legal move on a size-[n] cube, as a flat list. */
    fun allMoves(n: Int): List<Move> {
        val out = ArrayList<Move>()
        val maxLayer = (n + 1) / 2
        for (face in FaceId.entries) {
            for (layer in 1..maxLayer) {
                for (direction in Direction.entries) {
                    out += Move(face, layer, wide = false, direction = direction)
                    if (layer >= 2) out += Move(face, layer, wide = true, direction = direction)
                }
            }
        }
        return out
    }

    /** The 18 outer-layer turns. These never move a centre piece off its own face. */
    fun outerMoves(): List<Move> = FaceId.entries.flatMap { f ->
        Direction.entries.map { Move(f, 1, false, it) }
    }

    /**
     * Inner single-layer slice turns (layer >= 2, never the outer layer, never wide).
     *
     * On an odd cube the very middle layer is reachable from either side, so `3R` and `3L'` name the
     * same turn. Only the U, R and F spellings of that layer are kept, which keeps the reduction
     * search from exploring every sequence twice.
     */
    fun innerSliceMoves(n: Int): List<Move> {
        val out = ArrayList<Move>()
        val maxLayer = (n + 1) / 2
        val middleLayer = if (n % 2 == 1) maxLayer else -1
        for (face in FaceId.entries) {
            for (layer in 2..maxLayer) {
                if (layer == middleLayer && face !in setOf(FaceId.U, FaceId.R, FaceId.F)) continue
                for (direction in Direction.entries) out += Move(face, layer, false, direction)
            }
        }
        return out
    }
}
