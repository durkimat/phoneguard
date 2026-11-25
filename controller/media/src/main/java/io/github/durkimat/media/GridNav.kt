package io.github.durkimat.media

enum class Dir { UP, DOWN, LEFT, RIGHT }

/**
 * Keypad movement on a scrolling grid of [n] equal tiles, [cols] to a row, with no Android in it. Left and Right step through the
 * tiles in reading order (Left on the first tile and Right on the last stay put), Up and Down move a row, and a Down into a shorter
 * last row lands on the last tile. Nothing wraps: in a gallery of hundreds, jumping from the end to the start would be a surprise.
 */
object GridNav {
    fun move(sel: Int, n: Int, cols: Int, d: Dir): Int {
        if (n <= 0) return 0
        val s = sel.coerceIn(0, n - 1)
        return when (d) {
            Dir.LEFT -> maxOf(0, s - 1)
            Dir.RIGHT -> minOf(n - 1, s + 1)
            Dir.UP -> if (s >= cols) s - cols else s
            Dir.DOWN -> if (s + cols <= n - 1) s + cols else if ((s / cols) < ((n - 1) / cols)) n - 1 else s
        }
    }

    /** A page up or down: [rows] rows at a time, staying in the same column where there is one. */
    fun page(sel: Int, n: Int, cols: Int, rows: Int, down: Boolean): Int {
        var s = sel.coerceIn(0, maxOf(0, n - 1))
        repeat(rows) { s = move(s, n, cols, if (down) Dir.DOWN else Dir.UP) }
        return s
    }

    /** The first row on screen when [visible] rows fit: it moves only as far as needed to keep [sel] in view. */
    fun firstRow(sel: Int, n: Int, cols: Int, visible: Int, top: Int): Int {
        val rows = (n + cols - 1) / cols
        val row = sel / cols
        var t = top
        if (row < t) t = row else if (row > t + visible - 1) t = row - visible + 1
        return t.coerceIn(0, maxOf(0, rows - visible))
    }
}
