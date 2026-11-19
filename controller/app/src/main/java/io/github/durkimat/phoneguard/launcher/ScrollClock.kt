package io.github.durkimat.phoneguard.launcher

/**
 * The one clock every scrolling text line on a screen moves on: rest, travel to the end, rest, travel back. The travel is the LONGEST overflow among the lines
 * drawn in the frame, so lines that scroll together start, turn and stop together, never at different times or in different directions. A line that fits does
 * not scroll at all.
 */
object ScrollClock {
    const val SPEED = 90f      // design units a second
    const val REST_MS = 1500L  // pause at each end

    /** How far along (0..[travel]) the lines are at [nowMs], for a shared [travel]; 0 when nothing needs to scroll. */
    fun offset(travel: Float, nowMs: Long): Float {
        if (travel <= 0f) return 0f
        val moveMs = (travel / SPEED * 1000).toLong().coerceAtLeast(1L)
        val t = nowMs % (2 * (REST_MS + moveMs))
        return when {
            t < REST_MS -> 0f
            t < REST_MS + moveMs -> (t - REST_MS) / 1000f * SPEED
            t < 2 * REST_MS + moveMs -> travel
            else -> travel - (t - 2 * REST_MS - moveMs) / 1000f * SPEED
        }.coerceIn(0f, travel)
    }
}
