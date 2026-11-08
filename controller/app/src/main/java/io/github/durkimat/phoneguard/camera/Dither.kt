package io.github.durkimat.phoneguard.camera

/**
 * Turns a camera frame into the chunky one- or few-tone picture the viewfinder shows. Pure maths on a luminance accessor, so it
 * can be tested without a camera. Photos and video are saved in colour; only the screen is dithered.
 */
object Dither {
    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    /**
     * Average luminance (0 to 255) of each of cols by rows cells, looking at a w by h source that must be turned [rotation]
     * degrees clockwise (0, 90, 180 or 270) to be upright, and optionally mirrored (a front camera). [luma] returns the source
     * brightness at x, y.
     */
    fun sample(w: Int, h: Int, rotation: Int, mirror: Boolean, cols: Int, rows: Int, luma: (Int, Int) -> Int): IntArray {
        val out = IntArray(cols * rows)
        for (v in 0 until rows) for (u in 0 until cols) {
            var sum = 0
            for (k in 0 until 4) {
                val fx0 = (u + 0.25f + 0.5f * (k and 1)) / cols
                val fy = (v + 0.25f + 0.5f * (k shr 1)) / rows
                val fx = if (mirror) 1f - fx0 else fx0
                val sx: Float; val sy: Float
                when (rotation) {
                    90 -> { sx = fy; sy = 1f - fx }
                    180 -> { sx = 1f - fx; sy = 1f - fy }
                    270 -> { sx = 1f - fy; sy = fx }
                    else -> { sx = fx; sy = fy }
                }
                sum += luma((sx * w).toInt().coerceIn(0, w - 1), (sy * h).toInt().coerceIn(0, h - 1))
            }
            out[v * cols + u] = sum / 4
        }
        return out
    }

    /** Stretch the brightest and darkest 2% to the ends so a dull scene still uses the whole range. */
    fun levels(cells: IntArray): IntArray {
        if (cells.isEmpty()) return cells
        val hist = IntArray(256)
        for (c in cells) hist[c.coerceIn(0, 255)]++
        val cut = cells.size / 50
        var lo = 0; var acc = 0
        while (lo < 255 && acc + hist[lo] <= cut) { acc += hist[lo]; lo++ }
        var hi = 255; acc = 0
        while (hi > 0 && acc + hist[hi] <= cut) { acc += hist[hi]; hi-- }
        if (hi - lo < 16) return cells // a flat scene: leave it
        return IntArray(cells.size) { ((cells[it] - lo) * 255 / (hi - lo)).coerceIn(0, 255) }
    }

    /** True where a cell is ink (dark). [dither] uses an ordered 4 by 4 pattern; otherwise a plain threshold. */
    fun twoTone(cells: IntArray, cols: Int, dither: Boolean): BooleanArray {
        val lv = levels(cells)
        return BooleanArray(lv.size) { i ->
            val t = if (dither) bayer[((i / cols) and 3) * 4 + ((i % cols) and 3)] * 16 + 8 else 128
            lv[i] < t
        }
    }

    /** A smooth ramp between the screen colour (bright) and the ink colour (dark), as ARGB. */
    fun ramp(cells: IntArray, lcd: Int, ink: Int): IntArray {
        val lv = levels(cells)
        return IntArray(lv.size) { i ->
            val t = lv[i] / 255f
            fun mix(shift: Int): Int = (((ink shr shift) and 255) * (1 - t) + ((lcd shr shift) and 255) * t).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
    }
}
