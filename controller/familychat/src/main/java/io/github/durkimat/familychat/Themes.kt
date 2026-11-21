package io.github.durkimat.familychat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A chat's look, chosen by whoever holds the phone (no PIN): the colour of the child's own bubbles and a wallpaper. Wallpapers are drawn in code
 * (gradients, stripes, dots), so they cost almost nothing in size; "photo" is a picture the holder chose (a downsampled private copy). The keys of
 * the first set (sand, mint, sky, lilac, dots, stripes, confetti, night, photo) are kept, so earlier choices still work. This is the place to grow
 * a full theme (more per-chat colours) later.
 */
object Themes {
    data class Option(val key: String, val label: String)
    data class Bubble(val key: String, val label: String, val color: Int)

    val bubbles = listOf(
        Bubble("green", "Green", 0xFFD9F5D0.toInt()), Bubble("blue", "Blue", 0xFFD6E8FB.toInt()), Bubble("pink", "Pink", 0xFFFBDCE6.toInt()),
        Bubble("lilac", "Lilac", 0xFFE6DEF7.toInt()), Bubble("peach", "Peach", 0xFFFBE3CF.toInt()), Bubble("grey", "Grey", 0xFFE3E8ED.toInt()),
    )
    fun bubbleColor(key: String): Int = (bubbles.firstOrNull { it.key == key } ?: bubbles[0]).color

    val categories: List<Pair<String, List<Option>>> = listOf(
        "Colours" to listOf(Option("sand", "Sand"), Option("mint", "Mint"), Option("sky", "Sky"), Option("lilac", "Lilac"), Option("rose", "Rose"), Option("lemon", "Lemon"), Option("peach", "Peach"), Option("slate", "Slate")),
        "Patterns" to listOf(Option("dots", "Dots"), Option("stripes", "Stripes"), Option("confetti", "Confetti"), Option("checks", "Checks"), Option("rings", "Rings"), Option("diagonal", "Diagonal")),
        "Nature" to listOf(Option("hills", "Hills"), Option("beach", "Beach"), Option("sunset", "Sunset"), Option("forest", "Forest"), Option("snow", "Snow"), Option("desert", "Desert")),
        "Space" to listOf(Option("stars", "Stars"), Option("galaxy", "Galaxy"), Option("aurora", "Aurora"), Option("moon", "Moon"), Option("deepblue", "Deep blue"), Option("night", "Night")),
        "Fun" to listOf(Option("rainbow", "Rainbow"), Option("candy", "Candy"), Option("bubbles", "Bubbles"), Option("sunshine", "Sunshine"), Option("berry", "Berry"), Option("lime", "Lime")),
    )
    val allKeys: Set<String> = categories.flatMap { it.second }.map { it.key }.toSet() + "photo"

    /** A stored key that is no longer known (or never was) becomes the default, Sand. */
    fun normalize(key: String): String = if (key in allKeys) key else "sand"

    /** Wallpapers whose text must be light (dark grounds). */
    private val dark = setOf("stars", "galaxy", "aurora", "moon", "deepblue", "night", "forest")
    /** Mid-tone wallpapers where names and times sit on a small white chip, so they are readable anywhere. */
    private val chip = setOf("sunset", "desert", "hills", "beach", "berry")

    enum class Meta { PLAIN, LIGHT, CHIP }
    fun meta(key: String): Meta = if (key in dark) Meta.LIGHT else if (key in chip) Meta.CHIP else Meta.PLAIN

    // ---- the photo wallpaper
    fun file(c: Context, chat: String) = File(Media.dir(c, "wallpapers"), chat.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "x" } + ".jpg")

    /** Keeps a picture as this chat's wallpaper: downsampled to about 1080 px, saved privately. */
    fun savePhoto(c: Context, chat: String, uri: android.net.Uri): Boolean = try {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 1080) s *= 2
        val bmp = c.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = s }) }!!
        FileOutputStream(file(c, chat)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        true
    } catch (_: Throwable) { false }

    // ---- drawing
    /** A wallpaper as a drawable that paints itself to any size. */
    fun drawable(c: Context, rawKey: String, chat: String): Drawable {
        val key = normalize(rawKey)
        if (key == "photo") return Media.bitmap(file(c, chat).absolutePath, 1080)?.let { BitmapDrawable(c.resources, it).apply { gravity = android.view.Gravity.FILL } } ?: ColorDrawable(0xFFEFE9DF.toInt())
        val d = c.resources.displayMetrics.density
        return Painted { cv, r -> paint(cv, r, key, d) }
    }

    private class Painted(val draw: (Canvas, Rect) -> Unit) : Drawable() {
        override fun draw(canvas: Canvas) { canvas.save(); canvas.clipRect(bounds); draw.invoke(canvas, bounds); canvas.restore() }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(f: ColorFilter?) {}
        @Deprecated("") override fun getOpacity() = PixelFormat.OPAQUE
    }

    private fun col(hex: Long) = (0xFF000000L or hex).toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun fill(cv: Canvas, r: Rect, color: Int) { p.shader = null; p.color = color; cv.drawRect(r, p) }

    /** A CSS-style angled gradient (0 deg = to the top, 90 = to the right) across the rectangle, with hard or soft stops. */
    private fun grad(cv: Canvas, r: Rect, angle: Float, colors: IntArray, stops: FloatArray? = null) {
        val a = Math.toRadians(angle.toDouble()); val dx = sin(a).toFloat(); val dy = -cos(a).toFloat()
        val len = abs(r.width() * dx) + abs(r.height() * dy)
        val cx = r.exactCenterX(); val cy = r.exactCenterY()
        p.shader = LinearGradient(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2, colors, stops, Shader.TileMode.CLAMP)
        cv.drawRect(r, p); p.shader = null
    }

    /** Hard-edged bands from top to bottom: each colour holds until its stop. */
    private fun bands(cv: Canvas, r: Rect, colors: IntArray, ends: FloatArray) {
        var top = r.top.toFloat()
        for (i in colors.indices) { val bottom = r.top + r.height() * ends[i]; p.shader = null; p.color = colors[i]; cv.drawRect(r.left.toFloat(), top, r.right.toFloat(), bottom, p); top = bottom }
    }

    /** Stripes at an angle (45 or 135 deg), each colour [w] wide, repeating. */
    private fun stripes(cv: Canvas, r: Rect, angle: Float, a: Int, b: Int, w: Float) {
        fill(cv, r, b); p.shader = null; p.color = a
        cv.save(); cv.rotate(angle - 90f, r.exactCenterX(), r.exactCenterY())
        val span = hypot(r.width().toDouble(), r.height().toDouble()).toFloat()
        var x = r.exactCenterX() - span
        while (x < r.exactCenterX() + span) { cv.drawRect(x, r.exactCenterY() - span, x + w, r.exactCenterY() + span, p); x += 2 * w }
        cv.restore()
    }

    private fun dots(cv: Canvas, r: Rect, color: Int, radius: Float, step: Float, offset: Float = 0f) {
        p.shader = null; p.color = color
        var y = r.top + offset; while (y < r.bottom + step) { var x = r.left + offset; while (x < r.right + step) { cv.drawCircle(x, y, radius, p); x += step }; y += step }
    }

    private fun paint(cv: Canvas, r: Rect, key: String, d: Float) {
        when (key) {
            "sand" -> fill(cv, r, col(0xEFE9DF)); "mint" -> fill(cv, r, col(0xDDEFE3)); "sky" -> fill(cv, r, col(0xDCEAF6)); "lilac" -> fill(cv, r, col(0xE6E0F3))
            "rose" -> fill(cv, r, col(0xF6DDE4)); "lemon" -> fill(cv, r, col(0xF7F0CC)); "peach" -> fill(cv, r, col(0xF8E1D2)); "slate" -> fill(cv, r, col(0xDDE3EA))
            "dots" -> { fill(cv, r, col(0xEFE9DF)); dots(cv, r, col(0xD5CBB8), 1.6f * d, 22f * d, 11f * d) }
            "stripes" -> stripes(cv, r, 45f, col(0xE4EEE7), col(0xDCE9E1), 14f * d)
            "confetti" -> { fill(cv, r, col(0xF7F2EA)); dots(cv, r, col(0xF2B8C6), 3f * d, 46f * d, 23f * d); dots(cv, r, col(0xA9D4F5), 3f * d, 46f * d, 46f * d) }
            "checks" -> { fill(cv, r, col(0xF4EFE6)); p.shader = null; p.color = col(0xE9E2D5); val s = 20f * d
                var y = 0; while (y * s < r.height() + s) { var x = 0; while (x * s < r.width() + s) { if ((x + y) % 2 == 0) cv.drawRect(r.left + x * s, r.top + y * s, r.left + (x + 1) * s, r.top + (y + 1) * s, p); x++ }; y++ } }
            "rings" -> { val far = hypot(r.width().toDouble(), r.height().toDouble()).toFloat(); var i = (far / (10f * d)).toInt() + 1
                while (i >= 0) { p.shader = null; p.color = if (i % 2 == 0) col(0xE4EEE7) else col(0xF2F7F3); cv.drawCircle(r.left.toFloat(), r.top.toFloat(), (i + 1) * 10f * d, p); i-- } }
            "diagonal" -> stripes(cv, r, 135f, col(0xE6E0F3), col(0xF3EFFA), 10f * d)
            "hills" -> bands(cv, r, intArrayOf(col(0xBFE3F2), col(0x9AD19B), col(0x6FB57A)), floatArrayOf(0.5f, 0.7f, 1f))
            "beach" -> bands(cv, r, intArrayOf(col(0xCFEAF7), col(0x7CC4E0), col(0xF2E0B8)), floatArrayOf(0.45f, 0.75f, 1f))
            "sunset" -> grad(cv, r, 180f, intArrayOf(col(0xFFB38A), col(0xFF8FA3), col(0x9B6FD1)), floatArrayOf(0f, 0.5f, 1f))
            "forest" -> grad(cv, r, 180f, intArrayOf(col(0xDDEFD0), col(0x7FBF8A), col(0x3E8F5E)), floatArrayOf(0f, 0.6f, 1f))
            "snow" -> grad(cv, r, 180f, intArrayOf(col(0xEAF3FA), col(0xD3E5F2)))
            "desert" -> grad(cv, r, 180f, intArrayOf(col(0xFBE3B5), col(0xF2C078), col(0xD99A4E)), floatArrayOf(0f, 0.6f, 1f))
            "stars" -> { fill(cv, r, col(0x101B3C)); dots(cv, r, col(0xFFFFFF), 1f * d, 40f * d, 6f * d); dots(cv, r, col(0xCFE8FF), 1f * d, 60f * d, 26f * d) }
            "galaxy" -> grad(cv, r, 135f, intArrayOf(col(0x2B1B5A), col(0x6A3FA0), col(0xD86FB5)), floatArrayOf(0f, 0.55f, 1f))
            "aurora" -> grad(cv, r, 160f, intArrayOf(col(0x0F2B3A), col(0x1B8A7A), col(0x7BE0A8)), floatArrayOf(0f, 0.55f, 1f))
            "moon" -> { fill(cv, r, col(0x17232C)); p.color = col(0xF4EFD6); cv.drawCircle(r.left + r.width() * 0.75f, r.top + r.height() * 0.2f, 18f * d, p) }
            "deepblue" -> fill(cv, r, col(0x0E2A47)); "night" -> fill(cv, r, col(0x17232C))
            "rainbow" -> grad(cv, r, 180f, intArrayOf(col(0xFF9AA2), col(0xFFDAC1), col(0xFFF1A8), col(0xB5EAD7), col(0xA5D8FF), col(0xC7B6F5)))
            "candy" -> stripes(cv, r, 45f, col(0xFFD1DC), col(0xFFFFFF), 16f * d)
            "bubbles" -> { grad(cv, r, 180f, intArrayOf(col(0xBFE3F2), col(0xE6E0F3))); p.shader = null
                p.color = 0xB3FFFFFF.toInt(); cv.drawCircle(r.left + r.width() * 0.2f, r.top + r.height() * 0.3f, 14f * d, p); p.color = 0x8CFFFFFF.toInt(); cv.drawCircle(r.left + r.width() * 0.7f, r.top + r.height() * 0.6f, 22f * d, p) }
            "sunshine" -> { grad(cv, r, 180f, intArrayOf(col(0xCFEAF7), col(0xFFF6D6))); p.shader = null; p.color = col(0xFFE27A); cv.drawCircle(r.left + r.width() * 0.8f, r.top + r.height() * 0.15f, 26f * d, p) }
            "berry" -> grad(cv, r, 135f, intArrayOf(col(0xF7C6D9), col(0xC9B6F2)))
            "lime" -> grad(cv, r, 135f, intArrayOf(col(0xE4F6B8), col(0xB8EBC6)))
            else -> fill(cv, r, col(0xEFE9DF))
        }
    }
}
