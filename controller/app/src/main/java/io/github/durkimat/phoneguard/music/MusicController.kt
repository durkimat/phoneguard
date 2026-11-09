package io.github.durkimat.phoneguard.music

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import io.github.durkimat.phoneguard.notes.NoteService

/** What the Now playing screen shows. */
data class MusicInfo(
    val active: Boolean, val app: String, val title: String, val artist: String, val album: String,
    val playing: Boolean, val positionMs: Long, val durationMs: Long, val volumePct: Int,
    /** Where it is playing (Spotify account players only), and whether that is this phone. */
    val device: String = "", val thisPhone: Boolean = true, val shuffle: Boolean = false, val repeat: String = "off",
    /** Where it is playing from, such as "Playlist: study" (empty when unknown). */
    val from: String = "",
    /** "radio" for an internet radio station (live: no length, previous and next move between presets); empty for music. */
    val source: String = "", val buffering: Boolean = false,
) {
    companion object {
        val NONE = MusicInfo(false, "", "", "", "", false, 0, 0, 0)

        /** m:ss, or h:mm:ss for a long one. */
        fun clock(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}

/**
 * Any music app (Spotify first) is played in the app itself, in the background; this reads its media session and sends it the
 * usual transport commands, so the Nokia screens can be the remote. It needs the notification listener to be switched on (see
 * [NoteService]), which Phone Guard does itself.
 */
object MusicController {
    private const val SPOTIFY = "com.spotify.music"

    private fun controller(c: Context): MediaController? = try {
        val list = c.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(c, NoteService::class.java))
        list.firstOrNull { it.packageName == SPOTIFY } ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull { it.metadata != null }
    } catch (_: Exception) { null }

    fun volumePct(c: Context): Int {
        val am = c.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun info(c: Context): MusicInfo {
        val mc = controller(c) ?: return MusicInfo.NONE.copy(volumePct = volumePct(c))
        val md = mc.metadata
        val st = mc.playbackState
        val app = try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(mc.packageName, 0)).toString() } catch (_: Exception) { mc.packageName }
        val playing = st?.state == PlaybackState.STATE_PLAYING
        var pos = st?.position ?: 0L
        if (playing && st != null) pos += ((android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        return MusicInfo(
            active = true, app = app,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "",
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "",
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            playing = playing, positionMs = if (dur > 0) pos.coerceIn(0, dur) else pos.coerceAtLeast(0), durationMs = dur, volumePct = volumePct(c),
        )
    }

    fun playPause(c: Context) { controller(c)?.let { if (it.playbackState?.state == PlaybackState.STATE_PLAYING) it.transportControls.pause() else it.transportControls.play() } }
    fun seekTo(c: Context, ms: Long) { controller(c)?.transportControls?.seekTo(ms.coerceAtLeast(0)) }
    fun next(c: Context) { controller(c)?.transportControls?.skipToNext() }
    fun previous(c: Context) { controller(c)?.transportControls?.skipToPrevious() }

    /** One step up or down on the music volume (the phone's own volume, so headphones follow it). */
    fun volume(c: Context, up: Boolean) {
        c.getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
    }

    /** True when a music app we know is installed (so OK on an empty screen can open it). */
    fun spotifyInstalled(c: Context): Boolean = try { c.packageManager.getPackageInfo(SPOTIFY, 0); true } catch (_: Exception) { false }
    const val SPOTIFY_PKG = SPOTIFY
}
