package io.github.durkimat.phoneguard.alarm

import android.content.Context
import android.media.AudioManager

/**
 * Our own Ringtone and Messages levels. Android ties the ring and notification streams together (one index for both), and its lowest steps
 * are loud, so the two levels are kept here instead: eleven positions drawn as seven bars (the quietest bars have hidden sub-steps), each with
 * a gain applied to the saved tune files and to the tones we play ourselves. The system streams are held at full so the gain alone sets the
 * loudness, and a zero on one never touches the other.
 */
object SoundLevels {
    const val RING = "ring"; const val MSG = "sms"
    const val MAX = 10; const val DEFAULT = 8
    /** Gain at each position (a roughly perceptual curve: bar 1 is about 30 dB below full). */
    private val GAIN = doubleArrayOf(0.0, 0.02, 0.04, 0.07, 0.11, 0.16, 0.23, 0.34, 0.5, 0.7, 1.0)
    /** Bars shown at each position (bar 1 holds three positions, bar 2 two). */
    private val BARS = intArrayOf(0, 1, 1, 1, 2, 2, 3, 4, 5, 6, 7)

    private fun sp(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("tones", Context.MODE_PRIVATE)
    fun pos(c: Context, slot: String): Int = sp(c).getInt("vol_$slot", DEFAULT).coerceIn(0, MAX)
    fun setPos(c: Context, slot: String, p: Int) { sp(c).edit().putInt("vol_$slot", p.coerceIn(0, MAX)).apply(); if (slot == RING) ringLive = p.coerceIn(0, MAX) }
    /** The ringer step as last set, kept in memory so a ringing call can follow the keys live (-1 until first set). */
    @Volatile var ringLive = -1
    fun gain(c: Context, slot: String): Double = GAIN[pos(c, slot)]
    fun gainAt(p: Int): Double = GAIN[p.coerceIn(0, MAX)]
    fun bars(p: Int): Int = BARS[p.coerceIn(0, MAX)]

    /** Holds the ring and notification streams at full while the ringer is normal, so only our gain sets the loudness. */
    fun pin(c: Context) {
        try {
            val am = c.getSystemService(AudioManager::class.java)
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            for (s in intArrayOf(AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION)) {
                val m = am.getStreamMaxVolume(s)
                if (am.getStreamVolume(s) != m) am.setStreamVolume(s, m, 0)
            }
        } catch (_: Exception) { }
    }
}
