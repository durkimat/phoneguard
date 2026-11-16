package io.github.durkimat.phoneguard.core

import kotlin.math.roundToInt

/**
 * How many of the volume bar's blocks are filled. Zero only when the sound is off; the lowest sound level is one block and the highest is all of them, with the
 * levels between spread evenly. (The old rule rounded up, so on a stream with seven levels the first step showed two blocks and the step below it none: no
 * one-block level.) Pure, tested without a phone.
 */
object VolumeLevel {
    fun blocks(cur: Int, max: Int, n: Int = 10, muted: Boolean = false): Int {
        if (muted || cur <= 0) return 0
        if (max <= 1) return n
        return (1 + (cur.coerceAtMost(max) - 1) * (n - 1).toDouble() / (max - 1)).roundToInt().coerceIn(1, n)
    }
}
