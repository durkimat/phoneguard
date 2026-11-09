package io.github.durkimat.phoneguard.music

import android.content.Context
import android.os.Handler
import android.os.Looper

/** Stops the music after a while: off, 15, 30 or 60 minutes. Lives in the app's process (it survives screens, not a restart). */
object SleepTimer {
    private val steps = listOf(0, 15, 30, 60)
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var endsAt = 0L
    private var minutes = 0
    private var fire: Runnable? = null

    /** Minutes left, rounded up, or 0 when off. */
    fun leftMin(now: Long = System.currentTimeMillis()): Int = if (endsAt > now) ((endsAt - now + 59_999) / 60_000).toInt() else 0

    fun label(now: Long = System.currentTimeMillis()): String = leftMin(now).let { if (it > 0) "$it min" else "" }

    /** The next setting after the current one (off, 15, 30, 60, off...). */
    fun step(c: Context) {
        minutes = steps[(steps.indexOf(minutes).coerceAtLeast(0) + 1) % steps.size]
        fire?.let { ui.removeCallbacks(it) }; fire = null
        if (minutes == 0) { endsAt = 0; return }
        val app = c.applicationContext
        endsAt = System.currentTimeMillis() + minutes * 60_000L
        fire = Runnable { endsAt = 0; minutes = 0; stop(app) }.also { ui.postDelayed(it, minutes * 60_000L) }
    }

    /** Pause wherever it is playing: Spotify on any device, and the app on this phone. */
    private fun stop(c: Context) {
        Thread {
            try { Radio.stop(c) } catch (_: Exception) { }
            try { if (Spotify.signedIn(c)) Spotify.pause(c) } catch (_: Exception) { }
            try { if (MusicController.info(c).playing) MusicController.playPause(c) } catch (_: Exception) { }
        }.start()
    }
}
