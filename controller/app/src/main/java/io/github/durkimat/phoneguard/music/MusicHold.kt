package io.github.durkimat.phoneguard.music

import android.content.Context
import io.github.durkimat.phoneguard.Log

/**
 * Keeps music out of the way of a call or an alarm, and puts back only what it changed.
 *
 * A call (ringing, dialling or on) PAUSES the music: the radio fades out and lets go, music in an app on this phone (Spotify on this phone included) is paused at once
 * through its media session. An alarm or timer DUCKS the radio (its volume ramps down and comes back) and pauses the app's music, since only the radio has a volume of
 * our own to move. Spotify playing on another device (an Echo, say) is left alone. The alarm's own volume is never touched: only the music provider is.
 * The holds are a set of reasons; the music comes back when the last one is gone, and only if it is still the way we left it (still paused or ducked) and nothing else is playing,
 * so something the person paused themselves is never started.
 */
object MusicHold {
    const val CALL = "call"
    const val ALARM = "alarm"

    enum class Radio { NONE, DUCKED, PAUSED }

    /** What a set of reasons asks of the music: nothing, a duck (alarm only) or a pause (any call). Pure, for tests. */
    fun wanted(reasons: Set<String>): Radio = when { CALL in reasons -> Radio.PAUSED; reasons.isNotEmpty() -> Radio.DUCKED; else -> Radio.NONE }

    private val reasons = LinkedHashSet<String>()
    private var radio = Radio.NONE
    private var localPaused = false

    @Synchronized fun hold(c: Context, reason: String) {
        val first = reasons.isEmpty()
        if (!reasons.add(reason) && !first) return
        apply(c.applicationContext, first)
    }

    @Synchronized fun release(c: Context, reason: String) {
        if (!reasons.remove(reason)) return
        apply(c.applicationContext, false)
    }

    private fun apply(c: Context, first: Boolean) {
        try {
            val want = wanted(reasons)
            if (want == Radio.NONE) { undo(c); return }
            // the radio: only if it was playing when the hold began (or we already hold it)
            if (first && io.github.durkimat.phoneguard.music.Radio.playing && radio == Radio.NONE) {
                if (want == Radio.PAUSED) { io.github.durkimat.phoneguard.music.Radio.fadeStop(c); radio = Radio.PAUSED }
                else { io.github.durkimat.phoneguard.music.Radio.duck(); radio = Radio.DUCKED }
                Log.log(c, "music: radio ${radio.name.lowercase()} (${reasons.joinToString()})")
            } else if (radio == Radio.DUCKED && want == Radio.PAUSED) { // a call arrived while an alarm ducked it
                io.github.durkimat.phoneguard.music.Radio.fadeStop(c); radio = Radio.PAUSED
                Log.log(c, "music: radio paused (${reasons.joinToString()})")
            }
            // an app's music on this phone (a session that is playing): pause it
            if (first && !localPaused && MusicController.info(c).playing) {
                MusicController.pause(c); localPaused = true
                Log.log(c, "music: app paused (${reasons.joinToString()})")
            }
        } catch (e: Exception) { Log.log(c, "music: hold failed ${e.javaClass.simpleName}") }
    }

    private fun undo(c: Context) {
        try {
            val r = radio; val l = localPaused
            radio = Radio.NONE; localPaused = false
            val otherPlaying = io.github.durkimat.phoneguard.music.Radio.playing || (try { MusicController.info(c).playing } catch (_: Exception) { false })
            if (r == Radio.DUCKED) { io.github.durkimat.phoneguard.music.Radio.unduck(); Log.log(c, "music: radio volume back") }
            if (r == Radio.PAUSED && !io.github.durkimat.phoneguard.music.Radio.playing && io.github.durkimat.phoneguard.music.Radio.active) {
                // still the way we left it (paused, station still chosen) and no app playing in its place: bring it back
                if (!(try { MusicController.info(c).playing } catch (_: Exception) { false })) { io.github.durkimat.phoneguard.music.Radio.resumeFaded(c); Log.log(c, "music: radio resumed") }
            }
            if (l && !otherPlaying) { MusicController.play(c); Log.log(c, "music: app resumed") }
        } catch (e: Exception) { Log.log(c, "music: release failed ${e.javaClass.simpleName}") }
    }
}
