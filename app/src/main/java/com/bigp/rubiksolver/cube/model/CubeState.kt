package com.bigp.rubiksolver.cube.model

/**
 * A full cube, stored as one flat facelet array.
 *
 * Index layout matches [MoveEngine]: `face.ordinal * n * n + row * n + col`, faces in
 * [FaceId] declaration order (U, R, F, D, L, B).
 *
 * Instances are immutable from the outside; every mutator returns a new state.
 */
class CubeState private constructor(
    val size: CubeSize,
    internal val facelets: IntArray,
) {
    val n: Int get() = size.n

    fun colorAt(face: FaceId, row: Int, col: Int): FaceletColor =
        FaceletColor.ALL[facelets[index(face, row, col)]]

    fun colorAt(flatIndex: Int): FaceletColor = FaceletColor.ALL[facelets[flatIndex]]

    fun ordinalAt(flatIndex: Int): Int = facelets[flatIndex]

    fun index(face: FaceId, row: Int, col: Int): Int = face.ordinal * n * n + row * n + col

    /** Copy with one facelet replaced. */
    fun withColor(face: FaceId, row: Int, col: Int, color: FaceletColor): CubeState {
        val copy = facelets.copyOf()
        copy[index(face, row, col)] = color.ordinal
        return CubeState(size, copy)
    }

    /** Copy with a whole face replaced, given row-major colours. */
    fun withFace(face: FaceId, colors: List<FaceletColor>): CubeState {
        require(colors.size == n * n) { "expected ${n * n} colours, got ${colors.size}" }
        val copy = facelets.copyOf()
        val base = face.ordinal * n * n
        for (i in colors.indices) copy[base + i] = colors[i].ordinal
        return CubeState(size, copy)
    }

    fun faceColors(face: FaceId): List<FaceletColor> {
        val base = face.ordinal * n * n
        return List(n * n) { FaceletColor.ALL[facelets[base + it]] }
    }

    /** Applies a single move, returning the resulting state. */
    fun apply(move: Move): CubeState {
        val perm = MoveEngine.permutation(move, n)
        val out = IntArray(facelets.size)
        for (i in out.indices) out[i] = facelets[perm[i]]
        return CubeState(size, out)
    }

    /** Applies a whole algorithm in order. */
    fun apply(moves: List<Move>): CubeState {
        var s = this
        for (m in moves) s = s.apply(m)
        return s
    }

    /** True when every face shows a single colour. */
    val isSolved: Boolean
        get() {
            val per = n * n
            for (f in 0 until 6) {
                val first = facelets[f * per]
                for (i in 1 until per) if (facelets[f * per + i] != first) return false
            }
            return true
        }

    /** How many facelets differ from the target state. Used as a search heuristic. */
    fun mismatchCount(target: CubeState): Int {
        var c = 0
        for (i in facelets.indices) if (facelets[i] != target.facelets[i]) c++
        return c
    }

    /** Raw copy of the backing array, for renderers and persistence. */
    fun toIntArray(): IntArray = facelets.copyOf()

    /** Compact string form: 6 * n * n colour initials in face order. Round-trips via [decode]. */
    fun encode(): String = buildString(facelets.size) {
        for (v in facelets) append(FACE_CHARS[v])
    }

    override fun equals(other: Any?): Boolean =
        other is CubeState && other.size == size && other.facelets.contentEquals(facelets)

    override fun hashCode(): Int = 31 * size.hashCode() + facelets.contentHashCode()

    override fun toString(): String = "CubeState(${size.label}, ${encode()})"

    companion object {
        /** Colour initials, indexed by [FaceletColor.ordinal]. O for orange, so W Y R O B G. */
        private val FACE_CHARS = charArrayOf('W', 'Y', 'R', 'O', 'B', 'G')

        /**
         * A solved cube using the standard western scheme: U white, R red, F green, D yellow,
         * L orange, B blue. Only used as a default / preview; scanned cubes carry their own scheme.
         */
        val DEFAULT_SCHEME: Map<FaceId, FaceletColor> = mapOf(
            FaceId.U to FaceletColor.WHITE,
            FaceId.R to FaceletColor.RED,
            FaceId.F to FaceletColor.GREEN,
            FaceId.D to FaceletColor.YELLOW,
            FaceId.L to FaceletColor.ORANGE,
            FaceId.B to FaceletColor.BLUE,
        )

        fun solved(size: CubeSize, scheme: Map<FaceId, FaceletColor> = DEFAULT_SCHEME): CubeState {
            val per = size.n * size.n
            val arr = IntArray(6 * per)
            for (face in FaceId.entries) {
                val c = scheme.getValue(face).ordinal
                for (i in 0 until per) arr[face.ordinal * per + i] = c
            }
            return CubeState(size, arr)
        }

        fun fromOrdinals(size: CubeSize, ordinals: IntArray): CubeState {
            require(ordinals.size == size.totalFacelets) {
                "expected ${size.totalFacelets} facelets, got ${ordinals.size}"
            }
            return CubeState(size, ordinals.copyOf())
        }

        fun fromColors(size: CubeSize, colors: List<FaceletColor>): CubeState =
            fromOrdinals(size, IntArray(colors.size) { colors[it].ordinal })

        fun decode(size: CubeSize, encoded: String): CubeState {
            require(encoded.length == size.totalFacelets) {
                "expected ${size.totalFacelets} characters, got ${encoded.length}"
            }
            val arr = IntArray(encoded.length) { i ->
                val idx = FACE_CHARS.indexOf(encoded[i])
                require(idx >= 0) { "unknown colour character '${encoded[i]}'" }
                idx
            }
            return CubeState(size, arr)
        }

        /** Builds a random legal state by scrambling a solved cube. */
        fun scrambled(size: CubeSize, random: kotlin.random.Random, moveCount: Int = 40): CubeState {
            val moves = MoveEngine.allMoves(size.n)
            var s = solved(size)
            repeat(moveCount) { s = s.apply(moves[random.nextInt(moves.size)]) }
            return s
        }
    }
}
