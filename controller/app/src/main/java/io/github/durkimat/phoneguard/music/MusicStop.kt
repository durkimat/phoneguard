package io.github.durkimat.phoneguard.music

import android.content.Context
import io.github.durkimat.phoneguard.IdleReaper
import io.github.durkimat.phoneguard.Log

/**
 * A real Stop, not a pause. The radio is released (the station is forgotten, so the strip and Now playing drop it). Music in an app on this phone (Spotify on this
 * phone included) gets a transport stop and then the app is ended with the idle saver's hide-and-show trick, so its next play is a cold start. Spotify playing on
 * another device is paused (there is nothing of it here to close). The Sleep timer is cancelled. Used by the long press of OK on Now playing, the "Stop music" rows,
 * the 2 hour paused timeout and a mode that no longer allows music.
 */
object MusicStop {
    /** Spotify is shown on Home only when it is on this phone, and after a Stop it stays hidden until it is seen playing again. */
    @Volatile var spotifyDismissed = false

    fun radio(c: Context) { Radio.release(c); SleepTimer.cancel(); Log.log(c, "music: stop radio") }

    /** The app's music on this phone: stop it and end the app. Blocks briefly; call off the main thread. */
    fun app(c: Context, reason: String) {
        val pkg = MusicController.stop(c) ?: return
        spotifyDismissed = true
        if (pkg != c.packageName) {
            try { Thread.sleep(300) } catch (_: InterruptedException) { }
            val ok = IdleReaper.stop(c, pkg, "music stop: $reason")
            Log.log(c, "music: stop app $pkg ($reason) ${if (ok) "closed" else "not closed (paused and hidden from Home instead)"}")
        }
    }

    /** Spotify on any device other than this phone: pause it (Spotify has no stop). Network call, off the main thread. */
    fun spotifyElsewhere(c: Context) {
        try { if (Spotify.signedIn(c)) { Spotify.pause(c); spotifyDismissed = true; Log.log(c, "music: spotify paused (elsewhere)") } } catch (_: Exception) { }
    }

    fun spotify(c: Context, reason: String) { Thread { app(c, reason); spotifyElsewhere(c); SleepTimer.cancel() }.start() }

    /** Everything: the radio, the app on this phone and Spotify elsewhere. */
    fun all(c: Context, reason: String) {
        val app = c.applicationContext
        radio(app)
        Thread { app(app, reason); spotifyElsewhere(app); SleepTimer.cancel() }.start()
    }
}

/**
 * A paused source is stopped and dropped after a long idle pause ([PAUSED_LIMIT_MS], two hours): checked from the idle saver's tick, so the time is accurate to a few minutes.
 * A paused app is noticed the first time it is seen paused.
 */
object MusicWatch {
    const val PAUSED_LIMIT_MS = 2 * 60 * 60 * 1000L
    private var radioSince = 0L
    private var appSince = 0L

    /** True when something paused since [since] (0 = not paused) has been paused longer than the limit at [now]. */
    fun expired(since: Long, now: Long, limit: Long = PAUSED_LIMIT_MS) = since > 0 && now - since >= limit

    @Synchronized fun tick(c: Context, now: Long = System.currentTimeMillis()) {
        try {
            if (Radio.active && !Radio.playing) { if (radioSince == 0L) radioSince = now else if (expired(radioSince, now)) { MusicStop.radio(c); radioSince = 0L } } else radioSince = 0L
            val info = MusicController.info(c)
            if (info.active && !info.playing) {
                if (appSince == 0L) appSince = now
                else if (expired(appSince, now)) { appSince = 0L; Thread { MusicStop.app(c, "paused too long") }.start() }
            } else appSince = 0L
        } catch (_: Exception) { }
    }
}
