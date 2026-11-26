package io.github.durkimat.phoneguard.gallery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import io.github.durkimat.media.Graphemes
import io.github.durkimat.phoneguard.launcher.Fonts
import io.github.durkimat.phoneguard.launcher.Themes

/**
 * A keypad screen drawn in the launcher's theme and font on the 480 by 640 design grid, outside the launcher itself (the gallery,
 * the pickers): the title bar, text, the two soft keys and a list with one row chosen.
 */
abstract class LcdView(context: Context, themeId: String?, fontId: String?) : View(context) {
    private val theme = Themes.byId(themeId)
    val lcd = theme.lcd
    val ink = theme.ink
    private val font = Fonts.byId(fontId).id
    private val sizeScale = if (font == "vt323") 1.3f else 1f
    private val face: Typeface = try {
        when (font) {
            "sans" -> Typeface.SANS_SERIF
            "vt323" -> Typeface.createFromAsset(context.assets, "fonts/VT323-Regular.ttf")
            "pixelify" -> Typeface.createFromAsset(context.assets, "fonts/PixelifySans.ttf")
            else -> Typeface.MONOSPACE
        }
    } catch (_: Exception) { Typeface.MONOSPACE }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face }
    protected val fill = Paint().apply { color = ink }
    protected val stroke = Paint().apply { style = Paint.Style.STROKE; color = ink }
    protected val chunky = Paint().apply { isFilterBitmap = false }
    protected val smooth = Paint().apply { isFilterBitmap = true }

    init { setOnTouchListener { _, _ -> true } /* a keypad screen */ }

    protected fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, u: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT, color: Int = ink) {
        p.textSize = size * sizeScale * u; p.isFakeBoldText = bold; p.textAlign = align; p.color = color
        c.drawText(s, x * u, y * u, p)
    }

    protected fun fit(s: String, size: Float, maxW: Float, u: Float): String {
        p.textSize = size * sizeScale * u; p.isFakeBoldText = false
        if (p.measureText(s) <= maxW * u) return s
        var t = s
        while (t.length > 1 && p.measureText("$t\u2026") > maxW * u) t = Graphemes.dropLast(t)
        return "$t\u2026"
    }

    protected fun background(c: Canvas, title: String, u: Float) {
        c.drawColor(lcd)
        c.drawRect(0f, 0f, width.toFloat(), 52 * u, fill)
        text(c, title, 240f, 36f, 30f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
    }

    protected fun softKeys(c: Canvas, left: String, right: String, u: Float) {
        stroke.strokeWidth = 2 * u
        c.drawLine(0f, 576 * u, width.toFloat(), 576 * u, stroke)
        if (left.isNotEmpty()) { arrow(c, 22f, 606f, -1, u); text(c, left, 46f, 614f, 30f, u, bold = true) }
        arrow(c, 458f, 606f, 1, u); text(c, right, 434f, 614f, 30f, u, bold = true, align = Paint.Align.RIGHT)
    }

    /** A list of names with a count each, the [chosen] one inverted, scrolled to keep it in view. */
    protected fun countedList(c: Canvas, rows: List<Pair<String, Int>>, chosen: Int, u: Float) {
        val rowH = 52f; val visible = 9
        val first = (chosen - (visible - 1)).coerceAtLeast(0)
        for (k in 0 until visible) {
            val i = first + k
            if (i >= rows.size) break
            val y = 62f + k * rowH
            val on = i == chosen
            if (on) c.drawRect(0f, y * u, width.toFloat(), (y + rowH - 4) * u, fill)
            val col = if (on) lcd else ink
            text(c, fit(rows[i].first, 28f, 340f, u), 20f, y + 36f, 28f, u, bold = on, color = col)
            text(c, rows[i].second.toString(), 460f, y + 36f, 28f, u, align = Paint.Align.RIGHT, color = col)
        }
    }

    private fun arrow(c: Canvas, x: Float, y: Float, dir: Int, u: Float) {
        val path = Path().apply { moveTo((x + 10 * dir) * u, y * u); lineTo((x - 10 * dir) * u, (y - 12) * u); lineTo((x - 10 * dir) * u, (y + 12) * u); close() }
        c.drawPath(path, fill)
    }
}
