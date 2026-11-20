package io.github.durkimat.familychat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random

/**
 * Per-chat wallpapers: eight of our own, drawn in code, and "From my photos" (a downsampled copy kept in the app's private folder).
 * Whoever holds the phone chooses; no PIN. Later this can grow into a theme (wallpaper plus bubble colour): [Option] is the place for that.
 */
object Wallpapers {
    data class Option(val key: String, val label: String)
    val options = listOf(Option("sand", "Sand"), Option("mint", "Mint"), Option("sky", "Sky"), Option("lilac", "Lilac"), Option("dots", "Dots"), Option("stripes", "Stripes"), Option("confetti", "Confetti"), Option("night", "Night"))

    /** True for the dark wallpaper, whose text must be light. */
    fun dark(key: String) = key == "night"

    fun file(c: Context, chat: String) = File(Media.dir(c, "wallpapers"), safe(chat) + ".jpg")
    private fun safe(s: String) = s.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "x" }

    /** Keeps a picture the holder chose as this chat's wallpaper: downsampled to about 1080 px, saved privately. */
    fun savePhoto(c: Context, chat: String, uri: Uri): Boolean = try {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 1080) s *= 2
        val bmp = c.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = s }) }!!
        FileOutputStream(file(c, chat)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        true
    } catch (_: Throwable) { false }

    fun drawable(c: Context, key: String, chat: String): Drawable = when (key) {
        "photo" -> Media.bitmap(file(c, chat).absolutePath, 1080)?.let { BitmapDrawable(c.resources, it) } ?: ColorDrawable(Ui.SAND)
        "mint" -> ColorDrawable(0xFFDDF2E4.toInt())
        "sky" -> ColorDrawable(0xFFDCEBF8.toInt())
        "lilac" -> ColorDrawable(0xFFE8E0F4.toInt())
        "night" -> ColorDrawable(0xFF1B2733.toInt())
        "dots", "stripes", "confetti" -> pattern(c, key)
        else -> ColorDrawable(Ui.SAND)
    }

    private fun pattern(c: Context, key: String): Drawable {
        val size = Ui.dp(c, 120)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val ground = when (key) { "dots" -> 0xFFF4EFE6; "stripes" -> 0xFFEAF3EE; else -> 0xFFF7F1E8 }.toInt()
        cv.drawColor(ground)
        when (key) {
            "dots" -> { p.color = 0xFFD9CFBE.toInt(); for (y in 0..2) for (x in 0..2) cv.drawCircle(size / 6f + x * size / 3f + (y % 2) * size / 6f, size / 6f + y * size / 3f, size / 28f, p) }
            "stripes" -> { p.color = 0xFFD3E6DB.toInt(); p.strokeWidth = size / 10f; for (i in -2..4) cv.drawLine(i * size / 3f, 0f, i * size / 3f + size, size.toFloat(), p) }
            else -> { val r = Random(7); val cols = intArrayOf(0xFFF2A65A.toInt(), 0xFF6FB7E8.toInt(), 0xFFE97A9B.toInt(), 0xFF7BCB8E.toInt(), 0xFFB79BE6.toInt()); for (i in 0 until 14) { p.color = cols[i % cols.size]; val x = r.nextFloat() * size; val y = r.nextFloat() * size; cv.drawRoundRect(x, y, x + size / 9f, y + size / 28f, 4f, 4f, p) } }
        }
        return BitmapDrawable(c.resources, bmp).apply { setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT) }
    }
}
