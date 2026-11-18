package io.github.durkimat.phoneguard.alarm

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.TelephonyManager
import android.view.KeyEvent
import android.view.WindowManager
import io.github.durkimat.phoneguard.Clock
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.SoundEnforcer
import io.github.durkimat.phoneguard.Store
import io.github.durkimat.phoneguard.core.Engine
import io.github.durkimat.phoneguard.incall.LeftKey
import io.github.durkimat.phoneguard.launcher.Action
import io.github.durkimat.phoneguard.launcher.KeyMap
import io.github.durkimat.phoneguard.launcher.LauncherData
import io.github.durkimat.phoneguard.launcher.LauncherModel
import io.github.durkimat.phoneguard.launcher.NokiaSkin
import io.github.durkimat.phoneguard.launcher.PaintedSkin
import java.util.concurrent.CopyOnWriteArrayList

/** What is ringing now: [kind] is "alarm" or "timer" (a snooze ringing again is an alarm). Read by the ring screen. */
data class RingInfo(val kind: String, val id: Int, val tone: String, val time: String, val silentForCall: Boolean = false)

object RingState {
    @Volatile var info: RingInfo? = null; private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    fun set(i: RingInfo?) { info = i; main.post { listeners.forEach { it() } } }
    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    /** True while an alarm is sounding: the sound enforcer leaves Do Not Disturb alone then. */
    @Volatile var sounding = false
}

/** The exact alarms land here. An alarm in a mode that has alarms off is skipped (and a once-only one still switches off). */
class AlarmFire : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val kind = i.getStringExtra("kind") ?: return
        val id = i.getIntExtra("id", 0)
        val cfg = Store.config(c)
        if (kind == "alarm") AlarmStore.rang(c, id)
        if (kind == "timer") AlarmStore.saveTimer(c, AlarmStore.timer(c).reset())
        if (!Engine.alarms(cfg, Clock.now())) { Log.log(c, "alarms: $kind skipped, off in this mode"); return }
        val a = AlarmStore.alarms(c).firstOrNull { it.id == id }
        val tone = i.getStringExtra("tone")?.takeIf { it.isNotEmpty() } ?: Tones.DEFAULT
        val t = if (kind == "timer") "Timer" else java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(java.util.Date(Clock.now()))
        RingService.start(c, RingInfo(if (kind == "timer") "timer" else "alarm", id, if (kind == "timer") "beep" else tone, t))
        if (kind == "snooze") AlarmStore.clearSnooze(c)
        if (a == null && kind == "alarm") Log.log(c, "alarms: alarm $id rang but is gone")
    }
}

/** Plays the tune, vibrates and shows the ring screen until the alarm is stopped, snoozed or has rung for five minutes. */
class RingService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var wake: PowerManager.WakeLock? = null
    private var savedFilter = -1
    private var savedAlarmVol = -1
    private var info: RingInfo? = null
    private var startedAt = 0L
    private var gainAt = 0L
    private var prefs = ClockPrefs()
    private var mode = 0 // 0 nothing yet, 1 sounding, 2 vibrating only

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        when (i?.action) {
            ACT_SNOOZE -> { snooze(); return START_NOT_STICKY }
            ACT_STOP -> { stop(); return START_NOT_STICKY }
        }
        val ri = i?.let { RingInfo(it.getStringExtra("kind") ?: "alarm", it.getIntExtra("id", 0), it.getStringExtra("tone") ?: Tones.DEFAULT, it.getStringExtra("time") ?: "") } ?: return START_NOT_STICKY
        if (info != null) { stop(false) } // a second alarm at the same minute: one ring, the later one wins
        info = ri; mode = 0; startedAt = Clock.now(); prefs = AlarmStore.prefs(this)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "phoneguard:ring").also { it.acquire(6 * 60_000L) }
        RingState.sounding = true; RingState.set(ri)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(false) })
        try {
            if (nm.isNotificationPolicyAccessGranted && nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE) {
                savedFilter = nm.currentInterruptionFilter
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS) // total silence also mutes the alarm stream
            }
        } catch (e: Exception) { Log.log(this, "alarms: filter ${e.javaClass.simpleName}") }
        val show = PendingIntent.getActivity(this, 1, Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CH).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(if (ri.kind == "timer") "Timer" else "Alarm " + ri.time)
            .setCategory(Notification.CATEGORY_ALARM).setOngoing(true).setContentIntent(show).setFullScreenIntent(show, true).build()
        startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        try { startActivity(Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        ui.post(step)
        return START_NOT_STICKY
    }

    private val step = object : Runnable {
        override fun run() {
            val ri = info ?: return
            val now = Clock.now()
            val quiet = inCall() // a call is on or ringing: the alarm only vibrates, and the tune starts when the call is over
            if (quiet && mode != 2) { silence(); mode = 2; vibrate(true) }
            else if (!quiet && mode != 1) { sound(ri); mode = 1 }
            if (quiet != ri.silentForCall) { info = ri.copy(silentForCall = quiet); RingState.set(info) }
            track?.let { t -> t.setVolume(gain(now)) }
            if (now - startedAt > RING_MS) { if (ri.kind == "timer") stop() else snooze(auto = true); return }
            ui.postDelayed(this, 1000)
        }
    }

    private fun inCall() = try { getSystemService(TelephonyManager::class.java).callState != TelephonyManager.CALL_STATE_IDLE } catch (_: Exception) { false }

    /** 0..1: the alarm volume setting, and with a gradual alarm a climb over the first minute. */
    private fun gain(now: Long): Float {
        val level = (prefs.volume / AlarmMath.MAX_VOLUME.toFloat())
        val ramp = if (prefs.crescendo) (0.15f + 0.85f * ((now - startedAt) / 60_000f)).coerceAtMost(1f) else 1f
        return level * level * ramp // a squared curve: the steps near the bottom are quiet, as ears expect
    }

    private fun sound(ri: RingInfo) {
        silence()
        try {
            val am = getSystemService(AudioManager::class.java)
            if (savedAlarmVol < 0) savedAlarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) // our own volume setting scales the tune
            val pcm = Tones.render(Tones.byId(ri.tone))
            val frames = pcm.size.coerceAtLeast(1)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(frames * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            t.write(pcm, 0, pcm.size); t.setLoopPoints(0, frames, -1); t.setVolume(gain(Clock.now())); t.play()
            track = t
        } catch (e: Exception) { Log.log(this, "alarms: sound ${e.javaClass.simpleName}") }
        vibrate(true)
    }

    private fun silence() { try { track?.stop(); track?.release() } catch (_: Exception) { }; track = null; vibrate(inCall()) }

    private fun vibrate(on: Boolean) {
        val v = getSystemService(Vibrator::class.java)
        try { if (on) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 600), 0)) else v.cancel() } catch (_: Exception) { }
    }

    /** Snooze: ring again in nine minutes (a timer has no snooze: it stops). */
    private fun snooze(auto: Boolean = false) {
        val ri = info ?: return stop()
        if (ri.kind == "timer") return stop()
        val sp = createDeviceProtectedStorageContext().getSharedPreferences("alarms", MODE_PRIVATE)
        val n = if (auto) sp.getInt("autoSnoozes", 0) + 1 else 0
        sp.edit().putInt("autoSnoozes", n).apply()
        if (auto && n > 3) { stop(); return } // nobody is there: stop after three rings
        AlarmStore.setSnooze(this, Clock.now() + AlarmMath.SNOOZE_MIN * 60_000L, ri.tone)
        stop()
    }

    private fun stop(end: Boolean = true) {
        ui.removeCallbacks(step)
        silence(); vibrate(false)
        try { if (savedAlarmVol >= 0) getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVol, 0) } catch (_: Exception) { }
        savedAlarmVol = -1
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        info = null; savedFilter = -1
        RingState.sounding = false
        if (!end) return
        RingState.set(null)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        SoundEnforcer.reassert(this) // total silence goes back on if the mode wants it
    }

    override fun onDestroy() { if (info != null) stop(false); super.onDestroy() }

    companion object {
        const val CH = "pg_alarm"; const val NOTE = 4411
        const val ACT_SNOOZE = "io.github.durkimat.phoneguard.RING_SNOOZE"; const val ACT_STOP = "io.github.durkimat.phoneguard.RING_STOP"
        private const val RING_MS = 5 * 60_000L
        fun start(c: Context, i: RingInfo) {
            c.startForegroundService(Intent(c, RingService::class.java).putExtra("kind", i.kind).putExtra("id", i.id).putExtra("tone", i.tone).putExtra("time", i.time))
        }
        fun snooze(c: Context) { c.startService(Intent(c, RingService::class.java).setAction(ACT_SNOOZE)) }
        fun stop(c: Context) { c.startService(Intent(c, RingService::class.java).setAction(ACT_STOP)) }
    }
}

/** The ring screen: the Nokia look over the lock screen. Left key (or OK) snoozes an alarm; the right key stops it. */
class RingActivity : Activity() {
    private lateinit var skin: PaintedSkin
    private val model = LauncherModel()
    private val listener: () -> Unit = { update() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val look = io.github.durkimat.phoneguard.launcher.Look.current(this)
        model.theme = look.theme; model.font = look.font; model.accent = look.accent
        skin = if (look.skin == "mac") io.github.durkimat.phoneguard.launcher.MacSkin() else NokiaSkin()
        setContentView(skin.create(this))
        RingState.listen(listener)
        io.github.durkimat.phoneguard.HardKeys.menuKeyOverlay(this, false)
        LeftKey.listener = { perform(model.onKey(io.github.durkimat.phoneguard.launcher.Key.SOFT_LEFT, -1)) }
        update()
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); update() }

    override fun onDestroy() {
        RingState.unlisten(listener); LeftKey.listener = null
        io.github.durkimat.phoneguard.Controller.evaluateAsync(this, "ring screen closed")
        super.onDestroy()
    }

    private fun update() {
        val i = RingState.info
        if (i == null) { finish(); return }
        model.setRing(i.kind, i.time, i.silentForCall)
        skin.render(model, LauncherData.info(this))
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val k = KeyMap.of(ev.keyCode) ?: return super.dispatchKeyEvent(ev)
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) perform(model.onKey(k.first, k.second))
        return true // keys belong to the alarm: Back must not leave it ringing unseen
    }

    private fun perform(a: Action) {
        when (a) {
            Action.AlarmSnooze -> RingService.snooze(this)
            Action.AlarmStop -> RingService.stop(this)
            else -> {}
        }
    }
}
