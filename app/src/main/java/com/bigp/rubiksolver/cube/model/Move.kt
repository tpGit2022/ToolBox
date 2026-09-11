package com.bigp.rubiksolver.cube.model

/** Rotation amount of a single move. */
enum class Direction(val quarterTurns: Int, val suffix: String) {
    CW(1, ""),
    DOUBLE(2, "2"),
    CCW(3, "'");

    val inverse: Direction
        get() = when (this) {
            CW -> CCW
            CCW -> CW
            DOUBLE -> DOUBLE
        }

    companion object {
        fun fromQuarterTurns(q: Int): Direction = when (((q % 4) + 4) % 4) {
            1 -> CW
            2 -> DOUBLE
            3 -> CCW
            else -> throw IllegalArgumentException("Zero quarter turns is not a move")
        }
    }
}

/**
 * A single turn, generalised to N x N x N.
 *
 * @param face      the face the turn is named after and viewed from.
 * @param layer     how deep the turn reaches, 1-based from [face]. `1` is the outer layer.
 * @param wide      true when the turn carries every layer from 1 up to [layer] (a "w" turn);
 *                  false when it turns only layer [layer] on its own (an inner-slice turn).
 * @param direction how far to turn, viewed from outside [face].
 *
 * Notation examples: `R` (1, false, CW), `U'` (1, false, CCW), `Rw2` (2, true, DOUBLE),
 * `3F` (3, false, CW), `3Uw` (3, true, CW).
 */
data class Move(
    val face: FaceId,
    val layer: Int = 1,
    val wide: Boolean = false,
    val direction: Direction = Direction.CW,
) {
    init {
        require(layer >= 1) { "layer must be >= 1, was $layer" }
        require(!(wide && layer < 2)) { "a wide turn must span at least 2 layers" }
    }

    /** 0-based layer depths from [face] that this move rotates. */
    val depths: IntArray
        get() = if (wide) IntArray(layer) { it } else intArrayOf(layer - 1)

    val inverse: Move get() = copy(direction = direction.inverse)

    /** Standard cube notation, e.g. `Rw2`, `U'`, `3F`. */
    override fun toString(): String = buildString {
        if (wide) {
            if (layer > 2) append(layer)
            append(face.letter)
            append('w')
        } else {
            if (layer > 1) append(layer)
            append(face.letter)
        }
        append(direction.suffix)
    }

    /** True when both moves rotate exactly the same set of physical layers. */
    fun sameLayers(other: Move): Boolean =
        face == other.face && layer == other.layer && wide == other.wide

    companion object {
        /** Parses standard notation. Returns null when [text] is not a legal token. */
        fun parse(text: String): Move? {
            val t = text.trim()
            if (t.isEmpty()) return null
            var i = 0
            var prefix = 0
            var hadPrefix = false
            while (i < t.length && t[i].isDigit()) {
                prefix = prefix * 10 + (t[i] - '0')
                hadPrefix = true
                i++
            }
            // A written-out layer number has to be a real layer: "0R" and "00R" are not moves.
            if (hadPrefix && prefix < 1) return null
            if (i >= t.length) return null
            val face = FaceId.fromLetter(t[i]) ?: return null
            if (t[i].isLowerCase()) return null
            i++
            var wide = false
            if (i < t.length && (t[i] == 'w' || t[i] == 'W')) {
                wide = true
                i++
            }
            val direction = when {
                i >= t.length -> Direction.CW
                t[i] == '2' && i == t.length - 1 -> Direction.DOUBLE
                (t[i] == '\'' || t[i] == '’') && i == t.length - 1 -> Direction.CCW
                else -> return null
            }
            if (direction != Direction.CW) i++
            if (i != t.length) return null
            val layer = when {
                prefix > 0 -> prefix
                wide -> 2
                else -> 1
            }
            if (layer < 1) return null
            if (wide && layer < 2) return null
            return Move(face, layer, wide, direction)
        }

        /** Parses a whole space-separated algorithm. Throws on the first bad token. */
        fun parseAlgorithm(text: String): List<Move> =
            text.split(' ', '\t', '\n').filter { it.isNotBlank() }.map {
                parse(it) ?: throw IllegalArgumentException("Bad move token: $it")
            }
    }
}

/** Renders a move list back to a single notation string. */
fun List<Move>.toNotation(): String = joinToString(" ")

/** Reverses and inverts a sequence. */
fun List<Move>.inverseAlgorithm(): List<Move> = asReversed().map { it.inverse }
