package io.github.durkimat.phoneguard.camera

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The picture effects, as pure maths on a grid of brightness values (0 to 255). The same code makes the live viewfinder (a small
 * grid, [fine] false) and the saved copy of a photo (a big grid, [fine] true, with finer dots and lines).
 */
object Effects {
    /** The colours an effect paints with: (bright, dark). Dots, tones and the others that follow the launcher use its colours. */
    fun palette(e: Effect, lcd: Int, ink: Int): Pair<Int, Int> = when (e) {
        Effect.MONO -> 0xFFFFFFFF.toInt() to 0xFF000000.toInt()
        Effect.WARM -> 0xFFF6E7C8.toInt() to 0xFF2B1A0A.toInt()
        else -> lcd to ink
    }

    /** How many source pixels make one cell when a photo is turned into this effect (dots and outlines are chunkier). */
    fun cellFor(e: Effect) = when (e) { Effect.DOTS, Effect.OUTLINE -> 2; else -> 1 }

    private fun opaque(c: Int) = c or (0xFF shl 24)

    fun render(e: Effect, cells: IntArray, cols: Int, rows: Int, lcd: Int, ink: Int, fine: Boolean): IntArray {
        val (bright, dark) = palette(e, lcd, ink)
        return when (e) {
            Effect.DOTS -> { val t = Dither.twoTone(cells, cols, true); IntArray(t.size) { if (t[it]) opaque(dark) else opaque(bright) } }
            Effect.HALFTONE -> halftone(cells, cols, rows, bright, dark, if (fine) 9 else 5)
            Effect.POSTER -> poster(cells, bright, dark)
            Effect.OUTLINE -> outline(cells, cols, rows, bright, dark)
            Effect.SCAN -> scan(cells, cols, bright, dark, if (fine) 3 else 2)
            else -> Dither.ramp(cells, bright, dark) // Normal (when it has to be drawn), tones, mono, warm, dated frame
        }
    }

    /** A newspaper dot screen: each square of [period] cells holds one round dot, bigger where the picture is darker. */
    fun halftone(cells: IntArray, cols: Int, rows: Int, bright: Int, dark: Int, period: Int): IntArray {
        val lv = Dither.levels(cells)
        val bw = (cols + period - 1) / period; val bh = (rows + period - 1) / period
        val mean = IntArray(bw * bh)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var sum = 0; var n = 0
            for (y in by * period until minOf(rows, (by + 1) * period)) for (x in bx * period until minOf(cols, (bx + 1) * period)) { sum += lv[y * cols + x]; n++ }
            mean[by * bw + bx] = if (n > 0) sum / n else 255
        }
        return IntArray(cols * rows) { i ->
            val x = i % cols; val y = i / cols
            val d = 1f - mean[(y / period) * bw + (x / period)] / 255f
            val r = period * 0.72f * sqrt(d) // area grows with darkness; the 0.72 lets the darkest dots touch
            val dx = (x % period) + 0.5f - period / 2f; val dy = (y % period) + 0.5f - period / 2f
            if (dx * dx + dy * dy <= r * r) opaque(dark) else opaque(bright)
        }
    }

    /** Four flat shades between the screen colour and the ink. */
    fun poster(cells: IntArray, bright: Int, dark: Int): IntArray {
        val lv = Dither.levels(cells)
        return IntArray(lv.size) { i ->
            val q = (lv[i] * 4 / 256).coerceIn(0, 3) // 0 darkest
            val t = q / 3f
            fun mix(shift: Int) = (((dark shr shift) and 255) * (1 - t) + ((bright shr shift) and 255) * t).toInt().coerceIn(0, 255)
            opaque((mix(16) shl 16) or (mix(8) shl 8) or mix(0))
        }
    }

    /** Edges as ink lines on the screen colour (a Sobel filter on the stretched brightness). */
    fun outline(cells: IntArray, cols: Int, rows: Int, bright: Int, dark: Int): IntArray {
        val lv = Dither.levels(cells)
        fun at(x: Int, y: Int) = lv[y.coerceIn(0, rows - 1) * cols + x.coerceIn(0, cols - 1)]
        return IntArray(cols * rows) { i ->
            val x = i % cols; val y = i / cols
            val gx = -at(x - 1, y - 1) - 2 * at(x - 1, y) - at(x - 1, y + 1) + at(x + 1, y - 1) + 2 * at(x + 1, y) + at(x + 1, y + 1)
            val gy = -at(x - 1, y - 1) - 2 * at(x, y - 1) - at(x + 1, y - 1) + at(x - 1, y + 1) + 2 * at(x, y + 1) + at(x + 1, y + 1)
            if (abs(gx) + abs(gy) > 180) opaque(dark) else opaque(bright)
        }
    }

    /** Tones with every [period]th row darkened, like an old monitor. */
    fun scan(cells: IntArray, cols: Int, bright: Int, dark: Int, period: Int): IntArray {
        val base = Dither.ramp(cells, bright, dark)
        return IntArray(base.size) { i ->
            val c = base[i]
            if ((i / cols) % period != period - 1) c
            else { // pull the dark row 45 percent of the way to the ink colour
                fun mix(shift: Int) = ((((c shr shift) and 255) * 0.55f) + (((dark shr shift) and 255) * 0.45f)).toInt()
                opaque((mix(16) shl 16) or (mix(8) shl 8) or mix(0))
            }
        }
    }
}
