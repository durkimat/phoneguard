package io.github.durkimat.phoneguard.launcher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.github.durkimat.phoneguard.Log

/**
 * The system lock screen cannot be restyled, but its wallpaper can: this paints it in the current theme, like the
 * screen of an old phone (the colour of the LCD, faint pixel lines, a frame in the ink colour).
 */
object LockWallpaper {
    fun apply(c: Context, themeId: String?) {
        homeWallpaper(c)
        try {
            val t = Themes.byId(themeId)
            val dm = c.resources.displayMetrics
            val w = dm.widthPixels; val h = dm.heightPixels
            val key = "${t.id}:${w}x$h"
            val p = c.createDeviceProtectedStorageContext().getSharedPreferences("launcher", Context.MODE_PRIVATE)
            if (p.getString("lock_wp", null) == key) return
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            cv.drawColor(t.lcd)
            val line = Paint().apply { color = Color.argb(14, Color.red(t.ink), Color.green(t.ink), Color.blue(t.ink)); strokeWidth = 1f }
            var y = 0f
            while (y < h) { cv.drawLine(0f, y, w.toFloat(), y, line); y += 3f }
            var x = 0f
            while (x < w) { cv.drawLine(x, 0f, x, h.toFloat(), line); x += 3f }
            val frame = Paint().apply { color = t.ink; style = Paint.Style.STROKE; strokeWidth = w * 0.012f }
            val m = w * 0.04f
            cv.drawRect(m, m, w - m, h - m, frame)
            WallpaperManager.getInstance(c).setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
            p.edit().putString("lock_wp", key).apply()
        } catch (e: Exception) { Log.log(c, "lock wallpaper: ${e.javaClass.simpleName} ${e.message}") }
    }

    /** The home wallpaper is what shows behind the status bar between the boot animation and our home screen: the animation's own dark colour, not Android's light default. */
    private fun homeWallpaper(c: Context) {
        try {
            val p = c.createDeviceProtectedStorageContext().getSharedPreferences("launcher", Context.MODE_PRIVATE)
            if (p.getString("home_wp", null) == "dark1") return
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888); bmp.eraseColor(0xFF070A0F.toInt())
            WallpaperManager.getInstance(c).setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM)
            p.edit().putString("home_wp", "dark1").apply()
        } catch (e: Exception) { Log.log(c, "home wallpaper: ${e.javaClass.simpleName} ${e.message}") }
    }
}
