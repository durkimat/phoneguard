package io.github.durkimat.phoneguard.launcher

import kotlin.math.ceil

/**
 * Where a pattern may go when symbols sit on top of it (the UI rule in docs/ui-architecture.md): it clears a gap that follows each
 * symbol's own outline, areas that must read as one (the inside of the battery, a time) are cleared whole, and pieces too short to
 * read as part of the pattern are left out. Pure, on a grid of pixels, so it can be tested without a phone.
 */
object StripeWrap {
    /** Every pixel within [radius] of an [ink] pixel, in a [w] by [h] grid. */
    fun grow(ink: BooleanArray, w: Int, h: Int, radius: Float): BooleanArray {
        val n = ceil(radius).toInt()
        val disc = ArrayList<Pair<Int, Int>>()
        for (dy in -n..n) for (dx in -n..n) if (dx * dx + dy * dy <= radius * radius) disc.add(dx to dy)
        val out = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (!ink[y * w + x]) continue
            for ((dx, dy) in disc) { val a = x + dx; val b = y + dy; if (a in 0 until w && b in 0 until h) out[b * w + a] = true }
        }
        return out
    }

    /** Clears a whole rectangle (right and bottom not included). */
    fun block(clear: BooleanArray, w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int) {
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(h)) for (x in left.coerceAtLeast(0) until right.coerceAtMost(w)) clear[y * w + x] = true
    }

    /**
     * The pieces of one stripe, covering pixel rows [top] until [bottom], between columns [from] until [to]: the columns clear of
     * nothing in every one of those rows, as ranges. A piece shorter than [shortest] is dropped.
     */
    fun pieces(clear: BooleanArray, w: Int, top: Int, bottom: Int, from: Int, to: Int, shortest: Int): List<IntRange> {
        val h = clear.size / w
        val out = ArrayList<IntRange>()
        var start = -1
        for (x in from.coerceAtLeast(0)..to.coerceAtMost(w)) {
            val free = x < to.coerceAtMost(w) && (top.coerceAtLeast(0) until bottom.coerceAtMost(h)).none { clear[it * w + x] }
            if (free) { if (start < 0) start = x }
            else if (start >= 0) { if (x - start >= shortest) out.add(start until x); start = -1 }
        }
        return out
    }
}
