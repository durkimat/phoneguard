package io.github.durkimat.phoneguard.keys

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.launcher.Fonts
import io.github.durkimat.phoneguard.launcher.Themes

/**
 * A full-screen panel in the launcher's theme that covers whatever is happening underneath for a moment (Spotify starting up),
 * so the other app's screen is never seen. It always goes away by itself after a safety time.
 */
class ScreenCover(private val svc: AccessibilityService) {
    private val ui = Handler(Looper.getMainLooper())
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private val away = Runnable { hide() }

    fun show(message: String, maxMs: Long = 20_000) = showPanel(message.split("\n"), "", "", maxMs)

    /** Lines of text in the middle, and labels for the two keys under the screen (left, right). */
    fun showPanel(lines: List<String>, left: String, right: String, maxMs: Long = 20_000) {
        ui.post {
            val existing = view as? Cover
            if (existing != null) existing.set(lines, left, right) // change the words in place: taking the panel down and putting another up can show a frame of what is underneath
            else {
                val v = Cover(svc, lines, left, right)
                val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE)
                try { wm.addView(v, lp); view = v } catch (e: Exception) { Log.log(svc, "cover: ${e.javaClass.simpleName}") }
            }
            ui.removeCallbacks(away); ui.postDelayed(away, maxMs)
        }
    }

    fun hide() {
        ui.post { ui.removeCallbacks(away); view?.let { try { wm.removeView(it) } catch (_: Exception) { } }; view = null }
    }

    private class Cover(private val c: Context, private var lines: List<String>, private var left: String, private var right: String) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun set(l: List<String>, a: String, b: String) { lines = l; left = a; right = b; invalidate() }
        override fun onDraw(cv: Canvas) {
            val look = io.github.durkimat.phoneguard.launcher.Look.current(c)
            val t = look.colours()
            val font = Fonts.byId(look.font).id
            p.color = t.lcd; cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            p.color = t.ink
            p.typeface = try {
                when (font) { "sans" -> Typeface.SANS_SERIF; "vt323" -> Typeface.createFromAsset(c.assets, "fonts/VT323-Regular.ttf"); "pixelify" -> Typeface.createFromAsset(c.assets, "fonts/PixelifySans.ttf"); else -> Typeface.MONOSPACE }
            } catch (_: Exception) { Typeface.MONOSPACE }
            val u = width / 480f
            val scale = if (font == "vt323") 1.3f else 1f
            p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
            val first = height / 2f - (lines.size - 1) * 24f * u
            lines.forEachIndexed { i, line ->
                p.textSize = (if (lines.size == 1 || i == 0) 32f else 28f) * u * scale
                var t = line; while (t.length > 1 && p.measureText(t) > width - 24 * u) t = t.dropLast(1)
                cv.drawText(t, width / 2f, first + i * 48f * u, p)
            }
            // the two soft keys, like our own screens
            p.strokeWidth = 2 * u; cv.drawLine(0f, height - 64 * u, width.toFloat(), height - 64 * u, p)
            p.textSize = 30f * u * scale
            p.textAlign = Paint.Align.LEFT; if (left.isNotEmpty()) cv.drawText(left, 16 * u, height - 22 * u, p)
            p.textAlign = Paint.Align.RIGHT; if (right.isNotEmpty()) cv.drawText(right, width - 16 * u, height - 22 * u, p)
        }
    }
}
