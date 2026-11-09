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

    fun show(message: String, maxMs: Long = 20_000) {
        ui.post {
            if (view == null) {
                val v = Cover(svc, message)
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

    private class Cover(private val c: Context, private val message: String) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(cv: Canvas) {
            val prefs = c.createDeviceProtectedStorageContext().getSharedPreferences("launcher", Context.MODE_PRIVATE)
            val t = Themes.byId(prefs.getString("theme", Themes.DEFAULT))
            val font = Fonts.byId(prefs.getString("font", Fonts.DEFAULT)).id
            p.color = t.lcd; cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            p.color = t.ink
            p.typeface = try {
                when (font) { "sans" -> Typeface.SANS_SERIF; "vt323" -> Typeface.createFromAsset(c.assets, "fonts/VT323-Regular.ttf"); "pixelify" -> Typeface.createFromAsset(c.assets, "fonts/PixelifySans.ttf"); else -> Typeface.MONOSPACE }
            } catch (_: Exception) { Typeface.MONOSPACE }
            val u = width / 480f
            p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true; p.textSize = 32 * u * (if (font == "vt323") 1.3f else 1f)
            cv.drawText(message, width / 2f, height / 2f, p)
        }
    }
}
