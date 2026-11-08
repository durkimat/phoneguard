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
            Effect.HALFTONE -> halftone(cells, cols, rows, bright, dark, if (fine) 8 else 4)
            Effect.POSTER -> poster(cells, bright, dark)
            Effect.OUTLINE -> outline(cells, cols, rows, bright, dark)
            Effect.SCAN -> scan(cells, cols, bright, dark, if (fine) 3 else 2)
            else -> Dither.ramp(cells, bright, dark) // Normal (when it has to be drawn), tones, mono, warm, dated frame
        }
    }

    /**
     * A newspaper dot screen: round dots on a grid turned 45 degrees (as printers do, so the pattern does not stand out), each dot
     * as big as the picture is dark at its centre. [period] is the distance between dot centres, in cells.
     */
    fun halftone(cells: IntArray, cols: Int, rows: Int, bright: Int, dark: Int, period: Int): IntArray {
        val lv = Dither.levels(cells)
        val p = period.toFloat()
        val k = 0.70710678f
        // the dot centres: find the range of rotated grid cells the picture touches, then measure the picture at each centre
        fun rx(x: Float, y: Float) = (x + y) * k
        fun ry(x: Float, y: Float) = (y - x) * k
        val xs = floatArrayOf(rx(0f, 0f), rx(cols.toFloat(), 0f), rx(0f, rows.toFloat()), rx(cols.toFloat(), rows.toFloat()))
        val ys = floatArrayOf(ry(0f, 0f), ry(cols.toFloat(), 0f), ry(0f, rows.toFloat()), ry(cols.toFloat(), rows.toFloat()))
        val cx0 = Math.floor((xs.min() / p).toDouble()).toInt() - 1; val cx1 = Math.ceil((xs.max() / p).toDouble()).toInt() + 1
        val cy0 = Math.floor((ys.min() / p).toDouble()).toInt() - 1; val cy1 = Math.ceil((ys.max() / p).toDouble()).toInt() + 1
        val nx = cx1 - cx0 + 1; val ny = cy1 - cy0 + 1
        val dark01 = FloatArray(nx * ny)
        val half = (p / 2).toInt().coerceAtLeast(1)
        for (j in 0 until ny) for (i in 0 until nx) {
            val xr = (cx0 + i + 0.5f) * p; val yr = (cy0 + j + 0.5f) * p
            val x = ((xr - yr) * k).toInt(); val y = ((xr + yr) * k).toInt() // back to the picture
            var sum = 0; var n = 0
            for (yy in (y - half)..(y + half)) for (xx in (x - half)..(x + half)) if (xx in 0 until cols && yy in 0 until rows) { sum += lv[yy * cols + xx]; n++ }
            dark01[j * nx + i] = if (n > 0) 1f - sum / (255f * n) else 0f
        }
        return IntArray(cols * rows) { idx ->
            val x = (idx % cols) + 0.5f; val y = (idx / cols) + 0.5f
            val xr = rx(x, y); val yr = ry(x, y)
            val ci = Math.floor((xr / p).toDouble()).toInt(); val cj = Math.floor((yr / p).toDouble()).toInt()
            val d = dark01[(cj - cy0).coerceIn(0, ny - 1) * nx + (ci - cx0).coerceIn(0, nx - 1)]
            val r = p * 0.72f * sqrt(d) // the dot's area follows the darkness; 0.72 lets the darkest dots touch
            val dx = xr - (ci + 0.5f) * p; val dy = yr - (cj + 0.5f) * p
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
