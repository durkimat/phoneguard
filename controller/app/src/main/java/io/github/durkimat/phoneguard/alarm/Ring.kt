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
data class RingInfo(val kind: String, val id: Int, val tone: String, val time: String, val silentForCall: Boolean = false, val vol: Int = 5, val gradual: Boolean = false)

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
        val prefs = AlarmStore.prefs(c)
        val (vol, gradual) = when {
            kind == "snooze" -> AlarmStore.snoozeVol(c) to AlarmStore.snoozeGradual(c)
            a != null -> (if (a.vol > 0) a.vol else prefs.volume) to (a.gradual || (a.vol == 0 && prefs.crescendo))
            else -> prefs.volume to false
        }
        RingService.start(c, RingInfo(if (kind == "timer") "timer" else "alarm", id, if (kind == "timer") "beep" else tone, t, vol = vol, gradual = gradual))
        if (kind == "snooze") AlarmStore.clearSnooze(c)
        if (a == null && kind == "alarm") Log.log(c, "alarms: alarm $id rang but is gone")
    }
}

/** Plays the tune, vibrates and shows the ring screen until the alarm is stopped, snoozed or has rung for five minutes. */
class RingService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private var player: LoopPlayer? = null
    private var wake: PowerManager.WakeLock? = null
    private var savedFilter = -1
    private var info: RingInfo? = null
    private var startedAt = 0L
    private var gainAt = 0L
    private var mode = 0 // 0 nothing yet, 1 sounding, 2 vibrating only
    private var sawOn = false
    private var lastShow = 0L
    /** The power key puts the screen off: that quiets the alarm (snooze), as on any phone. Only once the screen has been on, so waking the phone is not mistaken for it. */
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_ON) sawOn = true
            else if (sawOn && info != null && Clock.now() - startedAt > 2000) { Log.log(c, "alarms: screen off, snoozed"); snooze() }
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        when (i?.action) {
            ACT_SNOOZE -> { snooze(); return START_NOT_STICKY }
            ACT_STOP -> { stop(); return START_NOT_STICKY }
        }
        val ri = i?.let { RingInfo(it.getStringExtra("kind") ?: "alarm", it.getIntExtra("id", 0), it.getStringExtra("tone") ?: Tones.DEFAULT, it.getStringExtra("time") ?: "", vol = it.getIntExtra("vol", 5), gradual = it.getBooleanExtra("gradual", false)) } ?: return START_NOT_STICKY
        if (info != null) { stop(false) } // a second alarm at the same minute: one ring, the later one wins
        info = ri; mode = 0; startedAt = Clock.now(); lastShow = startedAt
        sawOn = getSystemService(PowerManager::class.java).isInteractive
        try { registerReceiver(screen, android.content.IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }) } catch (_: Exception) { }
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "phoneguard:ring").also { it.acquire(6 * 60_000L) }
        RingState.sounding = true; RingState.set(ri)
        io.github.durkimat.phoneguard.music.MusicHold.hold(this, io.github.durkimat.phoneguard.music.MusicHold.ALARM) // the radio ducks, an app's music pauses; the alarm's own volume is untouched
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
            .setCategory(Notification.CATEGORY_ALARM).setOngoing(true).setContentIntent(show).setFullScreenIntent(show, true)
            .addAction(Notification.Action.Builder(null, "Stop", PendingIntent.getService(this, 2, Intent(this, RingService::class.java).setAction(ACT_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
            .apply { if (ri.kind != "timer") addAction(Notification.Action.Builder(null, "Snooze", PendingIntent.getService(this@RingService, 3, Intent(this@RingService, RingService::class.java).setAction(ACT_SNOOZE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()) }.build()
        startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        try { startActivity(Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Log.log(this, "alarms: ring screen not opened (${e.javaClass.simpleName}); the notification shows it") }
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
            // the ring screen went away (another window, Home) while it still sounds: bring it back, so there is always something to press
            if (!RingActivity.visible && getSystemService(PowerManager::class.java).isInteractive && now - lastShow > 4_000L) {
                lastShow = now; Log.log(this@RingService, "alarms: ring screen not in front, showing it again")
                try { startActivity(Intent(this@RingService, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
            }
            if (now - startedAt > RING_MS) { if (ri.kind == "timer") stop() else snooze(auto = true); return }
            ui.postDelayed(this, 1000)
        }
    }

    private fun inCall() = try { getSystemService(TelephonyManager::class.java).callState != TelephonyManager.CALL_STATE_IDLE } catch (_: Exception) { false }

    /** 0..1 applied to the samples themselves: this alarm's volume step, and with Gradual a climb over the first minute. */
    private fun gain(): Float { val ri = info ?: return 0f; return AlarmGain.level(ri.vol) * AlarmGain.ramp(Clock.now() - startedAt, ri.gradual) }

    private fun sound(ri: RingInfo) {
        silence()
        try {
            val p = LoopPlayer(this, Tones.render(Tones.byId(ri.tone), amp = 0.85, style = ToneStore.style(this)), { gain() })
            p.start(); player = p
        } catch (e: Exception) { Log.log(this, "alarms: sound ${e.javaClass.simpleName}") }
        vibrate(true)
    }

    private fun silence() { try { player?.stop() } catch (_: Exception) { }; player = null; vibrate(inCall()) }

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
        AlarmStore.setSnooze(this, Clock.now() + AlarmMath.SNOOZE_MIN * 60_000L, ri.tone, ri.vol, ri.gradual)
        stop()
    }

    private fun stop(end: Boolean = true) {
        ui.removeCallbacks(step)
        silence(); vibrate(false)
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        info = null; savedFilter = -1
        try { unregisterReceiver(screen) } catch (_: Exception) { }
        RingState.sounding = false
        if (!end) return
        io.github.durkimat.phoneguard.music.MusicHold.release(this, io.github.durkimat.phoneguard.music.MusicHold.ALARM)
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
            c.startForegroundService(Intent(c, RingService::class.java).putExtra("kind", i.kind).putExtra("id", i.id).putExtra("tone", i.tone).putExtra("time", i.time).putExtra("vol", i.vol).putExtra("gradual", i.gradual))
        }
        fun snooze(c: Context) { c.startService(Intent(c, RingService::class.java).setAction(ACT_SNOOZE)) }
        fun stop(c: Context) { c.startService(Intent(c, RingService::class.java).setAction(ACT_STOP)) }
    }
}

/** The ring screen: the Nokia look over the lock screen. Left key (or OK) snoozes an alarm; the right key stops it. */
class RingActivity : Activity() {
    companion object {
        @Volatile var visible = false
        private var lastBack = 0L
    }
    override fun onResume() { super.onResume(); visible = true; Log.log(this, "alarms: ring screen in front") }
    override fun onStop() { Log.log(this, "alarms: ring screen stopped"); super.onStop() }
    override fun onPause() {
        visible = false; Log.log(this, "alarms: ring screen paused (ringing ${RingState.sounding})")
        super.onPause()
        // something covered it while it still sounds: bring it back within about half a second (the service's own 4 s check stays as the backstop). Not during a call: the call screen wins then.
        val app = applicationContext
        if (RingState.sounding && io.github.durkimat.phoneguard.incall.CallState.info == null) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val now = android.os.SystemClock.elapsedRealtime()
            if (RingState.sounding && !visible && io.github.durkimat.phoneguard.incall.CallState.info == null && app.getSystemService(PowerManager::class.java).isInteractive && now - lastBack > 1_000L) {
                lastBack = now; Log.log(app, "alarms: ring screen covered, bringing it back")
                try { app.startActivity(Intent(app, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
            }
        }, 400L)
    }
    private lateinit var skin: PaintedSkin
    private val model = LauncherModel()
    private val listener: () -> Unit = { update() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // before the first unlock only device-protected storage can be read: the look then falls back to the defaults, but the alarm is still shown and stoppable
        val look = try { io.github.durkimat.phoneguard.launcher.Look.current(this) } catch (e: Exception) { null }
        if (look != null) { model.theme = look.theme; model.font = look.font; model.accent = look.accent }
        skin = if (look?.skin == "mac") io.github.durkimat.phoneguard.launcher.MacSkin() else NokiaSkin()
        setContentView(skin.create(this))
        RingState.listen(listener)
        try { io.github.durkimat.phoneguard.HardKeys.menuKeyOverlay(this, false) } catch (_: Exception) { }
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
        val home = try { LauncherData.info(this) } catch (e: Exception) { Log.log(this, "alarms: ring screen without home info (${e.javaClass.simpleName})"); fallbackInfo(i) }
        skin.render(model, home)
    }

    private fun fallbackInfo(i: RingInfo) = io.github.durkimat.phoneguard.launcher.HomeInfo(
        time = i.time, date = "", modeLine = "", badges = emptyList(), batteryPct = 100, charging = false, signalBars = 0, softLeft = "", softRight = "")

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        // every other key (volume, camera, side keys) snoozes, as on any phone: an alarm must never be left ringing with no way to quiet it
        val k = KeyMap.of(ev.keyCode) ?: return if (ev.keyCode == KeyEvent.KEYCODE_POWER) super.dispatchKeyEvent(ev) else {
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) perform(Action.AlarmSnooze); true }
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
