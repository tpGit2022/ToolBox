package com.bigp.rubiksolver.cube.model

/**
 * Slot lookups shared by the validator and the solvers.
 *
 * Everything is derived from [MoveEngine]'s coordinate map, so slot tables can never disagree with
 * the move tables.
 */
object Geometry {

    /** The eight corner slots, in the standard order URF, UFL, ULB, UBR, DFR, DLF, DBL, DRB. */
    val CORNER_SLOTS: List<Triple<FaceId, FaceId, FaceId>> = listOf(
        Triple(FaceId.U, FaceId.R, FaceId.F),
        Triple(FaceId.U, FaceId.F, FaceId.L),
        Triple(FaceId.U, FaceId.L, FaceId.B),
        Triple(FaceId.U, FaceId.B, FaceId.R),
        Triple(FaceId.D, FaceId.F, FaceId.R),
        Triple(FaceId.D, FaceId.L, FaceId.F),
        Triple(FaceId.D, FaceId.B, FaceId.L),
        Triple(FaceId.D, FaceId.R, FaceId.B),
    )

    /** The twelve edge slots, in the standard order UR, UF, UL, UB, DR, DF, DL, DB, FR, FL, BL, BR. */
    val EDGE_SLOTS: List<Pair<FaceId, FaceId>> = listOf(
        FaceId.U to FaceId.R,
        FaceId.U to FaceId.F,
        FaceId.U to FaceId.L,
        FaceId.U to FaceId.B,
        FaceId.D to FaceId.R,
        FaceId.D to FaceId.F,
        FaceId.D to FaceId.L,
        FaceId.D to FaceId.B,
        FaceId.F to FaceId.R,
        FaceId.F to FaceId.L,
        FaceId.B to FaceId.L,
        FaceId.B to FaceId.R,
    )

    private val reverseCache = HashMap<Int, HashMap<Long, Int>>()

    /** Reverse map from `(3D coordinate, outward normal)` to flat facelet index, for size [n]. */
    @Synchronized
    fun reverseMap(n: Int): HashMap<Long, Int> = reverseCache.getOrPut(n) {
        val map = HashMap<Long, Int>(6 * n * n * 2)
        for (face in FaceId.entries) {
            val nrm = MoveEngine.normalOf(face)
            for (r in 0 until n) for (c in 0 until n) {
                map[key(MoveEngine.coordOf(face, r, c, n), nrm, n)] = face.ordinal * n * n + r * n + c
            }
        }
        map
    }

    private fun key(coord: IntArray, normal: IntArray, n: Int): Long {
        var k = 0L
        for (v in coord) k = k * (n + 1) + v
        for (v in normal) k = k * 3 + (v + 1)
        return k
    }

    /** 3D coordinate of the cubie where the given faces meet, on a size-[n] cube. */
    fun cornerCoord(faces: List<FaceId>, n: Int): IntArray {
        val c = intArrayOf(-1, -1, -1)
        for (f in faces) {
            val nrm = MoveEngine.normalOf(f)
            for (a in 0..2) {
                if (nrm[a] > 0) c[a] = n - 1
                if (nrm[a] < 0) c[a] = 0
            }
        }
        return c
    }

    /** Flat facelet index of the sticker on [face] belonging to the cubie at [coord]. */
    fun faceletAt(coord: IntArray, face: FaceId, n: Int): Int =
        reverseMap(n).getValue(key(coord, MoveEngine.normalOf(face), n))

    /**
     * The three facelet indices of corner slot [slot] on a size-[n] cube, listed clockwise as seen
     * from outside the corner. [CORNER_SLOTS] is already stored in clockwise order, which this
     * asserts by checking the determinant of the three outward normals is negative.
     */
    fun cornerFacelets(slot: Int, n: Int): IntArray {
        val (a, b, c) = CORNER_SLOTS[slot]
        val faces = listOf(a, b, c)
        val coord = cornerCoord(faces, n)
        return IntArray(3) { faceletAt(coord, faces[it], n) }
    }

    /** Signed volume of the three outward normals of a corner slot; negative means clockwise. */
    fun cornerHandedness(slot: Int): Int {
        val (a, b, c) = CORNER_SLOTS[slot]
        val u = MoveEngine.normalOf(a)
        val v = MoveEngine.normalOf(b)
        val w = MoveEngine.normalOf(c)
        return u[0] * (v[1] * w[2] - v[2] * w[1]) -
            u[1] * (v[0] * w[2] - v[2] * w[0]) +
            u[2] * (v[0] * w[1] - v[1] * w[0])
    }

    /**
     * The two facelet indices of edge slot [slot] on an odd-sized cube of size [n]. The first entry
     * is the orientation reference: the U or D sticker when the edge touches U or D, otherwise the
     * F or B sticker. This is the standard Kociemba convention.
     */
    fun edgeFacelets(slot: Int, n: Int): IntArray {
        require(n % 2 == 1) { "middle edges only exist on odd cubes" }
        val (a, b) = EDGE_SLOTS[slot]
        val coord = cornerCoord(listOf(a, b), n)
        val mid = n / 2
        for (axis in 0..2) if (coord[axis] < 0) coord[axis] = mid
        return intArrayOf(faceletAt(coord, a, n), faceletAt(coord, b, n))
    }

    /**
     * Wing / edge-strip facelet pairs for any size. Each entry is one physical edge piece as a pair
     * of facelet indices, ordered the same way as [edgeFacelets]. For size `n` there are
     * `12 * (n - 2)` of them, grouped by edge slot.
     */
    fun edgeStripFacelets(slot: Int, n: Int): List<IntArray> {
        val (a, b) = EDGE_SLOTS[slot]
        val base = cornerCoord(listOf(a, b), n)
        val freeAxis = (0..2).first { base[it] < 0 }
        return (1..n - 2).map { t ->
            val coord = base.copyOf()
            coord[freeAxis] = t
            intArrayOf(faceletAt(coord, a, n), faceletAt(coord, b, n))
        }
    }

    /** Facelet indices of the centre block of [face] on a size-[n] cube (empty for n < 3). */
    fun centreFacelets(face: FaceId, n: Int): IntArray {
        if (n < 3) return IntArray(0)
        val out = ArrayList<Int>((n - 2) * (n - 2))
        for (r in 1 until n - 1) for (c in 1 until n - 1) out += face.ordinal * n * n + r * n + c
        return out.toIntArray()
    }
}
