package com.bigp.rubiksolver.color

import com.bigp.rubiksolver.cube.model.FaceletColor
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Reads sticker colours from a photo, entirely on the device.
 *
 * There is no computer-vision pipeline here on purpose. The capture screen draws the grid the user
 * lines the cube up with, so by the time a photo exists the squares are already where the app
 * expects them; finding them again with edge and perspective detection would be work the interface
 * has already done, and would cost a large native dependency for it.
 *
 * What is left is the part that actually decides the answer:
 *
 *  - **Sample the middle of each square.** The inner 60% only, so a chamfered sticker edge, the
 *    black plastic between stickers, or a slightly misaligned cube cannot pull the average.
 *  - **Take a median, not a mean.** A specular highlight is a handful of near-white pixels, and a
 *    mean would happily average them in. The median ignores them.
 *  - **Undo the lighting.** Grey the whole face by its own average, so a warm bulb or a blue-ish
 *    window shifts every square together instead of turning orange into red.
 *  - **Match in Lab, not RGB.** RGB distance disagrees with human vision exactly where this
 *    matters: red against orange. Lab is built so that equal distances look equally different.
 *  - **Say how sure it is.** Confidence is the gap between the best and second-best match. The
 *    correction screen uses it to flag the squares worth a second look, which is the difference
 *    between a user checking three squares and re-checking all fifty-four.
 */
class OnDeviceColorDetector : ColorDetector {

    override fun read(pixels: IntArray, width: Int, n: Int): FaceReading {
        require(width > 0) { "crop must have a positive width" }
        require(pixels.size >= width * width) { "expected a square crop of $width x $width" }
        require(n in 2..7) { "unsupported cube size $n" }

        val cell = width.toFloat() / n
        val inset = cell * SAMPLE_INSET

        // First pass: a median colour per square, in linear light.
        val samples = ArrayList<FloatArray>(n * n)
        val coords = ArrayList<IntArray>(n * n)
        for (row in 0 until n) {
            for (col in 0 until n) {
                val left = (col * cell + inset).toInt()
                val top = (row * cell + inset).toInt()
                val right = ((col + 1) * cell - inset).toInt().coerceAtLeast(left + 1)
                val bottom = ((row + 1) * cell - inset).toInt().coerceAtLeast(top + 1)
                samples += medianLinearRgb(pixels, width, left, top, right, bottom)
                coords += intArrayOf(row, col)
            }
        }

        // Second pass: undo the light.
        //
        // Two separate things need undoing, and they need undoing in this order. A colour *cast*
        // tints the channels unevenly -- a tungsten bulb pushes red up, which is what turns orange
        // into red -- and grey-world balance removes it, because a cube face shows a fixed spread of
        // colours so its average ought to be near neutral. *Exposure* is different: it scales all
        // three channels together, and grey-world cannot see it at all. Left alone it is the bigger
        // problem of the two, since a face shot at half brightness is a long way from the palette
        // even though its colours are perfectly distinct -- yellow lands nearer the reference for
        // orange than for yellow. Rescaling so the brightest sticker on the face matches the
        // brightest sticker in the palette fixes that, and works from the face's own contents
        // without needing to know the exposure.
        val cast = greyWorldGains(samples)
        val castCorrected = samples.map {
            floatArrayOf(it[0] * cast[0], it[1] * cast[1], it[2] * cast[2])
        }
        val exposure = exposureGain(castCorrected)
        val balanced = castCorrected.map {
            floatArrayOf(
                (it[0] * exposure).coerceAtMost(1f),
                (it[1] * exposure).coerceAtMost(1f),
                (it[2] * exposure).coerceAtMost(1f),
            )
        }

        val labs = balanced.map { linearRgbToLab(it) }
        val palette = FaceletColor.ALL.map { linearRgbToLab(srgbToLinear(it.srgb)) }

        val cells = ArrayList<SampledCell>(n * n)
        for (i in labs.indices) {
            var bestIndex = 0
            var best = Float.MAX_VALUE
            var second = Float.MAX_VALUE
            for (p in palette.indices) {
                val d = labDistance(labs[i], palette[p])
                if (d < best) {
                    second = best
                    best = d
                    bestIndex = p
                } else if (d < second) {
                    second = d
                }
            }
            // A clear win means the runner-up was far away; a coin toss means they were equal.
            val confidence = if (second <= 0f) 1f else ((second - best) / second).coerceIn(0f, 1f)
            cells += SampledCell(
                row = coords[i][0],
                col = coords[i][1],
                averageArgb = linearToArgb(balanced[i]),
                color = FaceletColor.ALL[bestIndex],
                confidence = confidence,
                rawLuminance = luminanceOf(samples[i]),
            )
        }
        return FaceReading(n, cells)
    }

    /**
     * Median of each channel over a patch, in linear light.
     *
     * Sampling on a stride keeps this cheap on a full-resolution photo without changing the answer:
     * a sticker is a flat colour, so a few hundred pixels describe it as well as ten thousand.
     */
    private fun medianLinearRgb(
        pixels: IntArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): FloatArray {
        val w = right - left
        val h = bottom - top
        val stride = max(1, min(w, h) / SAMPLES_PER_SIDE)
        val reds = ArrayList<Float>()
        val greens = ArrayList<Float>()
        val blues = ArrayList<Float>()
        var y = top
        while (y < bottom) {
            var x = left
            val rowBase = y * width
            while (x < right) {
                val argb = pixels[rowBase + x]
                reds += srgbChannelToLinear((argb shr 16) and 0xFF)
                greens += srgbChannelToLinear((argb shr 8) and 0xFF)
                blues += srgbChannelToLinear(argb and 0xFF)
                x += stride
            }
            y += stride
        }
        if (reds.isEmpty()) return floatArrayOf(0f, 0f, 0f)
        reds.sort(); greens.sort(); blues.sort()
        val mid = reds.size / 2
        return floatArrayOf(reds[mid], greens[mid], blues[mid])
    }

    /** Rec. 709 luminance of a linear RGB triple. */
    private fun luminanceOf(linear: FloatArray): Float =
        0.2126f * linear[0] + 0.7152f * linear[1] + 0.0722f * linear[2]

    /** Per-channel gains that push the face's average toward neutral grey. */
    private fun greyWorldGains(samples: List<FloatArray>): FloatArray {
        if (samples.isEmpty()) return floatArrayOf(1f, 1f, 1f)
        var r = 0f
        var g = 0f
        var b = 0f
        for (s in samples) {
            r += s[0]; g += s[1]; b += s[2]
        }
        val count = samples.size
        r /= count; g /= count; b /= count
        val grey = (r + g + b) / 3f
        if (grey <= 1e-5f) return floatArrayOf(1f, 1f, 1f)
        // Clamped, because a face that really is mostly one colour would otherwise be "corrected"
        // into something else entirely.
        fun gain(channel: Float) = (if (channel <= 1e-5f) 1f else grey / channel).coerceIn(0.75f, 1.35f)
        return floatArrayOf(gain(r), gain(g), gain(b))
    }

    /**
     * How much to scale the whole face so its brightest square matches the palette's brightest.
     *
     * Uses the brightest square rather than the average, because how bright a face *should* be
     * depends on which colours happen to be on it -- a face of six white stickers and a face of six
     * blue ones have very different averages under identical light. The brightest sticker is a far
     * steadier anchor: every scrambled face has something light on it, and white is the top of the
     * palette. Clamped, so a face that genuinely has no bright sticker is not stretched into one.
     */
    private fun exposureGain(samples: List<FloatArray>): Float {
        if (samples.isEmpty()) return 1f
        val brightest = samples.maxOf { maxOf(it[0], maxOf(it[1], it[2])) }
        if (brightest <= 1e-4f) return 1f
        // The range has to be generous. Halving a photo's brightness in sRGB terms is closer to
        // dividing linear light by seven, because of the gamma curve between the two, so a gain
        // ceiling that sounds ample in sRGB is nowhere near enough here.
        return (PALETTE_PEAK / brightest).coerceIn(0.5f, 40f)
    }

    companion object {
        /** Brightest channel value in the reference palette, in linear light. */
        private val PALETTE_PEAK: Float = FaceletColor.ALL.maxOf { colour ->
            val linear = srgbToLinear(colour.srgb)
            maxOf(linear[0], maxOf(linear[1], linear[2]))
        }

        /** Fraction of each square trimmed off every side before sampling. */
        private const val SAMPLE_INSET = 0.20f

        /** Roughly how many pixels to look at along each side of a square. */
        private const val SAMPLES_PER_SIDE = 12

        fun srgbChannelToLinear(value: Int): Float {
            val v = value / 255f
            return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        }

        fun srgbToLinear(argb: Int): FloatArray = floatArrayOf(
            srgbChannelToLinear((argb shr 16) and 0xFF),
            srgbChannelToLinear((argb shr 8) and 0xFF),
            srgbChannelToLinear(argb and 0xFF),
        )

        private fun linearChannelToSrgb(value: Float): Int {
            val v = value.coerceIn(0f, 1f)
            val s = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
            return (s * 255f).toInt().coerceIn(0, 255)
        }

        fun linearToArgb(linear: FloatArray): Int =
            (0xFF shl 24) or
                (linearChannelToSrgb(linear[0]) shl 16) or
                (linearChannelToSrgb(linear[1]) shl 8) or
                linearChannelToSrgb(linear[2])

        /** Linear sRGB to CIE L*a*b*, through XYZ with a D65 white point. */
        fun linearRgbToLab(rgb: FloatArray): FloatArray {
            val x = 0.4124f * rgb[0] + 0.3576f * rgb[1] + 0.1805f * rgb[2]
            val y = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]
            val z = 0.0193f * rgb[0] + 0.1192f * rgb[1] + 0.9505f * rgb[2]
            val fx = labF(x / 0.95047f)
            val fy = labF(y)
            val fz = labF(z / 1.08883f)
            return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
        }

        private fun labF(t: Float): Float =
            if (t > 0.008856f) cbrt(t) else (7.787f * t + 16f / 116f)

        /**
         * Distance in Lab, with lightness weighted down.
         *
         * Two photos of the same sticker differ far more in how bright it is than in what colour it
         * is: a shadow, a glare, the angle to the lamp. Hue and chroma are what identify a sticker,
         * so lightness gets a third of the say.
         */
        fun labDistance(a: FloatArray, b: FloatArray): Float {
            val dl = (a[0] - b[0]) * 0.35f
            val da = a[1] - b[1]
            val db = a[2] - b[2]
            return sqrt(dl * dl + da * da + db * db)
        }

        /**
         * True when a whole scan looks too dark or too washed out to trust, so the app can suggest
         * better light before the user gets as far as a failed solve.
         */
        fun looksBadlyLit(readings: List<FaceReading>): Boolean {
            if (readings.isEmpty()) return false
            val cells = readings.flatMap { it.cells }
            if (cells.isEmpty()) return false
            // Judged on the raw exposure, not the corrected colours: correction is what makes a dim
            // photo readable, so asking the corrected pixels how dark the room was would always get
            // the answer "fine".
            val medianRaw = readings.map { it.medianRawLuminance }.sorted().let {
                it[it.size / 2]
            }
            val weakConfidence = cells.count { it.confidence < 0.18f }
            return medianRaw < DARK_LUMINANCE || weakConfidence > cells.size / 4
        }

        /**
         * Linear luminance below which a scan counts as too dark to trust.
         *
         * Roughly a quarter of mid-grey. Correction can still read a face this dark, but with little
         * signal left between the colours, so it is worth suggesting more light.
         */
        private const val DARK_LUMINANCE = 0.05f

        /** Absolute difference in lightness, exposed for tests and tuning. */
        fun lightnessGap(a: FloatArray, b: FloatArray): Float = abs(a[0] - b[0])
    }
}
