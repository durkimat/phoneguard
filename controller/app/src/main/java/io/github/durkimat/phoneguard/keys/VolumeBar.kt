package io.github.durkimat.phoneguard.keys

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.incall.CallState
import io.github.durkimat.phoneguard.launcher.Fonts
import io.github.durkimat.phoneguard.launcher.Themes
import kotlin.math.roundToInt

/**
 * Our own volume bar, in the Nokia theme and font, in place of Android's slider. The side-button service sees the volume keys
 * (screen on), changes the right volume itself and draws this small panel for a moment at the top of the screen, over
 * whatever app is in front. It never takes touches or focus.
 */
class VolumeBar(private val svc: AccessibilityService) {
    private val ui = Handler(Looper.getMainLooper())
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val am = svc.getSystemService(AudioManager::class.java)
    private var view: Bar? = null
    private val hide = Runnable { view?.let { try { wm.removeView(it) } catch (_: Exception) { } }; view = null }

    /** What the keys change right now: the call, the music being played on this phone, or else the ringer. */
    private fun ringing() = io.github.durkimat.phoneguard.incall.CallState.info?.phase == io.github.durkimat.phoneguard.launcher.CallPhase.RINGING || try { svc.getSystemService(android.telephony.TelephonyManager::class.java).callState == android.telephony.TelephonyManager.CALL_STATE_RINGING } catch (_: Exception) { false }

    fun stream(): Pair<Int, String> = when {
        ringing() -> AudioManager.STREAM_RING to "RING" // an incoming call is ringing: the keys set the ringer (the first press also quiets it, as the system does)
        CallState.info != null || am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL to "CALL"
        am.isMusicActive -> AudioManager.STREAM_MUSIC to "MUSIC"
        else -> AudioManager.STREAM_RING to "RING"
    }

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "volume-keys") }

    /**
     * Called on the main thread from the key filter, which the system only waits ~500 ms for (if it is late the key goes on to the system and
     * its own volume slider shows). So this returns at once: the audio work runs on one worker thread, in order, and only the drawing is posted back.
     */
    fun press(up: Boolean) {
        // an alarm or timer is ringing: any volume key snoozes it (a timer stops), whatever else the keys do
        if (io.github.durkimat.phoneguard.alarm.RingState.sounding) { io.github.durkimat.phoneguard.alarm.RingService.snooze(svc); return }
        // the Music screen is open and the music is on another Spotify device (the Echo): the keys change that device
        io.github.durkimat.phoneguard.launcher.LauncherActivity.remoteVolume?.invoke(up)?.let { (pct, name) -> show(pct, 100, name, false); return }
        val queued = System.nanoTime()
        worker.execute {
            try { work(up) } catch (e: Exception) { Log.log(svc, "volume: ${e.javaClass.simpleName}") }
            val ms = (System.nanoTime() - queued) / 1_000_000
            if (ms > 100) Log.log(svc, "volume: a key took $ms ms to handle (queued and worked)")
        }
    }

    private fun work(up: Boolean) {
        val (s, label) = stream()
        // the ringer's own level (SoundLevels) is what the side keys step when the phone supplies the ringtone; the streams stay pinned at full
        val lv = io.github.durkimat.phoneguard.alarm.SoundLevels
        val tones = io.github.durkimat.phoneguard.alarm.ToneStore
        if (s == AudioManager.STREAM_RING && am.ringerMode == AudioManager.RINGER_MODE_NORMAL && (ringing() || tones.pick(svc, tones.RING) != tones.STOCK)) {
            // while a call rings the keys change the level live (CallRinger follows it); they never quiet the ring
            Log.log(svc, "volume: ring level key ${if (up) "up" else "down"} (ringing ${ringing()}, now ${lv.pos(svc, lv.RING)})")
            val p = (lv.pos(svc, lv.RING) + if (up) 1 else -1).coerceIn(0, lv.MAX)
            lv.setPos(svc, lv.RING, p); lv.pin(svc)
            Thread { tones.levelChanged(svc, tones.RING) }.start()
            show(p, lv.MAX, label, false) // eleven steps, ten blocks: one press moves the bar by exactly one block (or into the empty one at zero)
            if (!ringing()) beep(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE, lv.gainAt(p) * tones.RING_AMP / 0.6)
            return
        }
        // one press is one visible block: at most ten steps, each a whole number of the stream's own indexes
        try {
            if (s == AudioManager.STREAM_RING) am.adjustStreamVolume(s, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_ALLOW_RINGER_MODES)
            else {
                val max = am.getStreamMaxVolume(s).coerceAtLeast(1); val cells = minOf(max, 10)
                val pos = Math.round(am.getStreamVolume(s) * cells / max.toFloat()) + if (up) 1 else -1
                am.setStreamVolume(s, Math.round(pos.coerceIn(0, cells) * max / cells.toFloat()).coerceAtLeast(if (s == AudioManager.STREAM_VOICE_CALL) 1 else 0), 0)
            }
        } catch (e: Exception) { Log.log(svc, "volume: ${e.javaClass.simpleName}") }
        show(s, label)
        // a short beep at the new level tells the person where it is left; with music playing the music says it
        if (s == AudioManager.STREAM_VOICE_CALL) beep(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION, 1.0)
        else if (s == AudioManager.STREAM_RING && am.ringerMode == AudioManager.RINGER_MODE_NORMAL && !ringing()) beep(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE, 1.0)
    }

    private var beepRun = Runnable { }
    /** Quick presses: the sample in progress is cut at once and only the last level plays, after a short pause with no further press. */
    private fun beep(usage: Int, gain: Double) {
        val tones = io.github.durkimat.phoneguard.alarm.ToneStore
        tones.stopSample(); ui.removeCallbacks(beepRun)
        beepRun = Runnable { Thread { tones.sample(svc, usage, "", gain, blip = true) }.start() }
        ui.postDelayed(beepRun, 120)
    }

    private fun show(stream: Int, label: String) {
        val cur = am.getStreamVolume(stream); val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        val cells = minOf(max, 10)
        show(Math.round(cur * cells / max.toFloat()), cells, label, stream == AudioManager.STREAM_RING && am.ringerMode != AudioManager.RINGER_MODE_NORMAL)
    }

    private fun show(cur: Int, max: Int, label: String, silent: Boolean) { ui.post { showNow(cur, max, label, silent) } }

    private fun showNow(cur: Int, max: Int, label: String, silent: Boolean) {
        val v = view ?: Bar(svc).also { b ->
            val scale = svc.resources.displayMetrics.widthPixels / 480f
            val lp = WindowManager.LayoutParams((330 * scale).roundToInt(), (64 * scale).roundToInt(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; lp.y = (46 * scale).roundToInt()
            try { wm.addView(b, lp); view = b } catch (e: Exception) { Log.log(svc, "volume bar: ${e.javaClass.simpleName}") }
        }
        v.set(cur, max, label, silent)
        ui.removeCallbacks(hide); ui.postDelayed(hide, 1600)
    }

    /** The panel: speaker, ten blocks, and the stream's name, drawn from the launcher's own theme and font. */
    private class Bar(private val c: Context) : View(c) {
        private var cur = 0; private var max = 1; private var label = ""; private var silent = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        fun set(cur: Int, max: Int, label: String, silent: Boolean) { this.cur = cur; this.max = max; this.label = label; this.silent = silent; invalidate() }

        override fun onDraw(cv: Canvas) {
            val look = io.github.durkimat.phoneguard.launcher.Look.current(c)
            val t = look.colours()
            val font = Fonts.byId(look.font).id
            val u = width / 330f
            p.isAntiAlias = false
            p.color = t.ink; cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)           // the frame
            p.color = t.lcd; cv.drawRect(4 * u, 4 * u, width - 4 * u, height - 4 * u, p)         // the screen
            p.color = t.ink
            // speaker, drawn in pixels: a box and a cone, with a cross when sound is off
            val sx = 16 * u; val cy = height / 2f
            cv.drawRect(sx, cy - 6 * u, sx + 8 * u, cy + 6 * u, p)
            val cone = android.graphics.Path().apply { moveTo(sx + 8 * u, cy - 6 * u); lineTo(sx + 20 * u, cy - 14 * u); lineTo(sx + 20 * u, cy + 14 * u); lineTo(sx + 8 * u, cy + 6 * u); close() }
            cv.drawPath(cone, p)
            val muted = silent || cur == 0
            if (muted) { p.strokeWidth = 3 * u; cv.drawLine(sx + 26 * u, cy - 9 * u, sx + 40 * u, cy + 9 * u, p); cv.drawLine(sx + 26 * u, cy + 9 * u, sx + 40 * u, cy - 9 * u, p) }
            // ten blocks, filled up to the level
            val n = if (max in 1..10) max else 10; val filled = io.github.durkimat.phoneguard.core.VolumeLevel.blocks(cur, max, n, muted)
            val x0 = 66 * u; val gap = 3 * u; val bw = (150 * u - gap * (n - 1)) / n; val bh = 26 * u
            for (i in 0 until n) {
                val l = x0 + i * (bw + gap); val top = cy - bh / 2
                if (i < filled) cv.drawRect(l, top, l + bw, top + bh, p)
                else { p.style = Paint.Style.STROKE; p.strokeWidth = 2 * u; cv.drawRect(l + u, top + u, l + bw - u, top + bh - u, p); p.style = Paint.Style.FILL }
            }
            // the stream's name, small, in the chosen font
            p.typeface = try {
                when (font) { "sans" -> Typeface.SANS_SERIF; "vt323" -> Typeface.createFromAsset(c.assets, "fonts/VT323-Regular.ttf"); "pixelify" -> Typeface.createFromAsset(c.assets, "fonts/PixelifySans.ttf"); else -> Typeface.MONOSPACE }
            } catch (_: Exception) { Typeface.MONOSPACE }
            p.isAntiAlias = true; p.textSize = 22 * u * (if (font == "vt323") 1.3f else 1f); p.textAlign = Paint.Align.RIGHT
            cv.drawText(label, width - 14 * u, cy + 8 * u, p)
        }
    }
}
