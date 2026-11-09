package io.github.durkimat.phoneguard.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.Store
import io.github.durkimat.phoneguard.core.RadioStation
import io.github.durkimat.phoneguard.launcher.LauncherActivity
import org.json.JSONArray

/**
 * Internet radio, the second music source after Spotify. The station list comes from the parent (HA) or, without one, from the list the
 * phone was built with. The stream plays in [RadioService]; this object is what the screens talk to and where they read the state.
 */
object Radio {
    data class State(
        val index: Int = -1, val station: String = "", val url: String = "",
        val playing: Boolean = false, val buffering: Boolean = false,
        /** What the station says is on now ("Artist - Title"), when it sends that. */
        val nowPlaying: String = "", val error: String = "", val startedAt: Long = 0,
    )

    @Volatile var state = State()
        private set

    /** A station is chosen (playing, connecting or paused). */
    val active: Boolean get() = state.url.isNotEmpty()
    val playing: Boolean get() = state.playing || state.buffering

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun onChange(l: () -> Unit) { listeners.add(l) }
    fun offChange(l: () -> Unit) { listeners.remove(l) }
    internal fun set(s: State) { state = s; Handler(Looper.getMainLooper()).post { listeners.forEach { it() } } }

    @Volatile private var builtIn: List<RadioStation>? = null

    /** The stations in preset order: the parent's list, else the built-in one. */
    fun stations(c: Context): List<RadioStation> {
        val mode = io.github.durkimat.phoneguard.core.Engine.effective(Store.config(c), Store.boost(c), io.github.durkimat.phoneguard.Clock.now()).radio
        if (mode == "off") return emptyList()
        val base = Store.config(c)?.radioStations?.takeIf { it.isNotEmpty() } ?: (builtIn ?: loadBuiltIn(c).also { builtIn = it })
        if (mode != "any") return base
        return arrange(base, RadioFavs.slots(c)).let { list -> list + RadioFavs.all(c).filter { f -> list.none { it.url == f.url } } }
    }

    /**
     * The list with the child's own presets in place. A station set as preset N takes row N: if it was already elsewhere in the list the
     * two swap places, and if it came from outside the list the station it displaces moves to the end. Nothing is ever lost.
     */
    fun arrange(base: List<RadioStation>, slots: Map<Int, RadioStation>): List<RadioStation> {
        val list = base.toMutableList(); val displaced = mutableListOf<RadioStation>()
        for ((n, st) in slots.toSortedMap()) {
            val at = n - 1
            if (at >= list.size) { if (list.none { it.url == st.url }) list.add(st); continue }
            val old = list[at]; if (old.url == st.url) continue
            val from = list.indexOfFirst { it.url == st.url }
            if (from >= 0) list[from] = old else displaced.add(old)
            list[at] = st
        }
        return list + displaced.filter { d -> list.none { it.url == d.url } }
    }

    private fun loadBuiltIn(c: Context): List<RadioStation> = try {
        val a = JSONArray(c.assets.open("radio/stations.json").bufferedReader().use { it.readText() })
        (0 until a.length()).map { RadioStation(a.getJSONObject(it).getString("name"), a.getJSONObject(it).getString("url")) }
    } catch (e: Exception) { Log.log(c, "radio: built-in stations: ${e.javaClass.simpleName}"); emptyList() }

    /** Start a preset (0 is the first). Other music on this phone or in the Spotify account is paused first. */
    fun play(c: Context, index: Int) {
        val list = stations(c)
        val s = list.getOrNull(index) ?: return
        start(c, index, s.name, s.url)
    }

    /** The playing station's place in the list has changed (a preset was set): follow it. */
    fun reindex(c: Context) { val s = state; if (s.url.isNotEmpty()) set(s.copy(index = stations(c).indexOfFirst { it.url == s.url })) }

    /** Start any station by name and address (a station found by browsing or searching). */
    fun playUrl(c: Context, name: String, url: String) = start(c, stations(c).indexOfFirst { it.url == url }, name, url)

    private fun start(c: Context, index: Int, name: String, url: String) {
        val app = c.applicationContext
        set(State(index, name, url, playing = false, buffering = true, startedAt = System.currentTimeMillis()))
        Thread {
            try { if (Spotify.signedIn(app)) Spotify.pause(app) } catch (_: Exception) { }
            try { if (MusicController.info(app).playing) MusicController.playPause(app) } catch (_: Exception) { }
        }.start()
        send(app, RadioService.PLAY, name, url)
    }

    /** Pause (the stream is let go) or, if paused, play the same station again from live. */
    fun toggle(c: Context) {
        val s = state
        if (s.url.isEmpty()) return
        if (playing) stop(c) else start(c, s.index, s.station, s.url)
    }

    /** Let the stream go; the station stays chosen so play brings it back. */
    fun stop(c: Context) {
        val s = state
        if (s.url.isEmpty() && !playing) return
        set(s.copy(playing = false, buffering = false, nowPlaying = "", error = ""))
        send(c.applicationContext, RadioService.STOP, "", "")
    }

    fun next(c: Context) = step(c, 1)
    fun previous(c: Context) = step(c, -1)
    private fun step(c: Context, by: Int) {
        val n = stations(c).size
        if (n == 0) return
        val cur = state.index
        play(c, if (cur < 0) 0 else (cur + by + n) % n)
    }

    private fun send(c: Context, action: String, name: String, url: String) {
        val i = Intent(c, RadioService::class.java).setAction(action).putExtra("name", name).putExtra("url", url)
        try { if (action == RadioService.PLAY) c.startForegroundService(i) else c.startService(i) } catch (e: Exception) { Log.log(c, "radio: could not start the service: ${e.javaClass.simpleName}") }
    }
}

/** Plays the stream with ExoPlayer in the background, with a media session so headset buttons and the lock screen can control it. */
class RadioService : Service() {
    companion object { const val PLAY = "radio.play"; const val STOP = "radio.stop"; private const val CHANNEL = "radio"; private const val ID = 71 }

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var retried = false
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            PLAY -> {
                val name = i.getStringExtra("name").orEmpty(); val url = i.getStringExtra("url").orEmpty()
                if (url.isEmpty()) { stopSelf(); return START_NOT_STICKY }
                startForeground(ID, notification(name), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                retried = false
                begin(url)
            }
            else -> { release(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { release(); super.onDestroy() }

    private fun begin(url: String) {
        player?.release()
        val p = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(st: Int) { sync(p) }
            override fun onIsPlayingChanged(isPlaying: Boolean) { sync(p) }
            override fun onMetadata(m: Metadata) {
                for (k in 0 until m.length()) (m[k] as? IcyInfo)?.title?.trim()?.takeIf { it.isNotEmpty() }?.let { t -> Radio.set(Radio.state.copy(nowPlaying = t)); session?.let { updateSession() } }
            }
            override fun onPlayerError(e: PlaybackException) {
                Log.log(this@RadioService, "radio: player error ${e.errorCodeName}")
                if (!retried) { retried = true; main.postDelayed({ if (player === p) { p.prepare(); p.play() } }, 3000); Radio.set(Radio.state.copy(buffering = true, playing = false)); return }
                Radio.set(Radio.state.copy(playing = false, buffering = false, error = "Cannot play this station"))
                updateSession()
            }
        })
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
        player = p
        if (session == null) session = MediaSession(this, "PhoneGuardRadio").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { Radio.toggle(this@RadioService) }
                override fun onPause() { Radio.stop(this@RadioService) }
                override fun onStop() { Radio.stop(this@RadioService) }
                override fun onSkipToNext() { Radio.next(this@RadioService) }
                override fun onSkipToPrevious() { Radio.previous(this@RadioService) }
            })
            isActive = true
        }
        updateSession()
    }

    private fun sync(p: ExoPlayer) {
        if (player !== p) return
        val buffering = p.playbackState == Player.STATE_BUFFERING || (p.playWhenReady && p.playbackState == Player.STATE_IDLE)
        val playing = p.isPlaying
        if (playing) retried = false
        Radio.set(Radio.state.copy(playing = playing, buffering = buffering && !playing, error = if (playing || buffering) "" else Radio.state.error))
        updateSession()
    }

    private fun updateSession() {
        val s = session ?: return
        val st = Radio.state
        s.setMetadata(android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, st.nowPlaying.ifEmpty { st.station })
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, if (st.nowPlaying.isEmpty()) "" else st.station).build())
        val code = if (st.playing) PlaybackState.STATE_PLAYING else if (st.buffering) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED
        s.setPlaybackState(PlaybackState.Builder().setState(code, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS).build())
    }

    private fun release() {
        player?.release(); player = null
        session?.release(); session = null
    }

    private fun notification(name: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Radio", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(name).setContentText("Radio").setOngoing(true).setContentIntent(open).build()
    }
}
