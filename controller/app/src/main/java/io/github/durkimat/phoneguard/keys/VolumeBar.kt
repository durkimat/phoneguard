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
    fun stream(): Pair<Int, String> = when {
        CallState.info != null || am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL to "CALL"
        am.isMusicActive -> AudioManager.STREAM_MUSIC to "MUSIC"
        else -> AudioManager.STREAM_RING to "RING"
    }

    fun press(up: Boolean) {
        // the Music screen is open and the music is on another Spotify device (the Echo): the keys change that device
        io.github.durkimat.phoneguard.launcher.LauncherActivity.remoteVolume?.invoke(up)?.let { (pct, name) -> show(pct, 100, name, false); return }
        val (s, label) = stream()
        try {
            am.adjustStreamVolume(s, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_ALLOW_RINGER_MODES)
        } catch (e: Exception) { Log.log(svc, "volume: ${e.javaClass.simpleName}") }
        show(s, label)
    }

    private fun show(stream: Int, label: String) {
        val cur = am.getStreamVolume(stream); val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        show(cur, max, label, stream == AudioManager.STREAM_RING && am.ringerMode != AudioManager.RINGER_MODE_NORMAL)
    }

    private fun show(cur: Int, max: Int, label: String, silent: Boolean) {
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
            val prefs = c.createDeviceProtectedStorageContext().getSharedPreferences("launcher", Context.MODE_PRIVATE)
            val t = Themes.byId(prefs.getString("theme", Themes.DEFAULT))
            val font = Fonts.byId(prefs.getString("font", Fonts.DEFAULT)).id
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
            val n = 10; val filled = if (muted) 0 else ((cur * n + max - 1) / max).coerceIn(1, n)
            val x0 = 66 * u; val bw = 12 * u; val gap = 3 * u; val bh = 26 * u
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
