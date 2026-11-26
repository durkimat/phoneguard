package io.github.durkimat.phoneguard.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import io.github.durkimat.phoneguard.launcher.Fonts
import io.github.durkimat.phoneguard.launcher.Themes

/**
 * Draws the gallery in the launcher's theme and font on the 480 by 640 design grid: a title bar, then a grid of pictures, one picture,
 * or the list of folders, and two soft keys. Pictures come from [thumb] (the grid) and [big] (the viewer); both may be null while loading.
 */
class GalleryView(context: Context, private val model: GalleryModel, themeId: String?, fontId: String?) : View(context) {
    companion object {
        const val TILE = 150f
        const val GAP = 6f
        const val LEFT = 9f
        const val TOP = 58f
        /** The viewer's picture box (design pixels) and its size in 3-pixel cells. */
        const val CELL = 3
        const val BOX_L = 6f; const val BOX_T = 58f; const val BOX_W = 468f; const val BOX_H = 480f
    }

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
    private val fill = Paint().apply { color = ink }
    private val stroke = Paint().apply { style = Paint.Style.STROKE; color = ink }
    private val chunky = Paint().apply { isFilterBitmap = false }
    private val smooth = Paint().apply { isFilterBitmap = true }

    /** A grid tile's picture: a small dithered bitmap (drawn chunky) or a colour thumbnail (drawn smooth, cropped square). Set by the activity. */
    @Volatile var thumb: (GItem) -> Bitmap? = { null }
    /** The viewer's picture, dithered or colour (see [model]); null while loading. */
    @Volatile var big: Bitmap? = null
    /** Shown over the viewer for a moment ("Cannot open"). */
    @Volatile var notice: String? = null

    init { setOnTouchListener { _, _ -> true } /* a keypad screen */ }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, u: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT, color: Int = ink) {
        p.textSize = size * sizeScale * u; p.isFakeBoldText = bold; p.textAlign = align; p.color = color
        c.drawText(s, x * u, y * u, p)
    }

    private fun fit(s: String, size: Float, maxW: Float, u: Float): String {
        p.textSize = size * sizeScale * u; p.isFakeBoldText = false
        if (p.measureText(s) <= maxW * u) return s
        var n = s.length
        while (n > 1 && p.measureText(s.take(n) + "…") > maxW * u) n--
        return s.take(n) + "…"
    }

    override fun onDraw(c: Canvas) {
        val u = width / 480f
        c.drawColor(lcd)
        c.drawRect(0f, 0f, width.toFloat(), 52 * u, fill)
        val n = model.items.size
        val title = if (model.view == GView.FOLDERS) "Folders" else fit(model.album.name, 30f, 300f, u) + if (n > 0) "  ${model.sel + 1}/$n" else ""

        text(c, title, 240f, 36f, 30f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
        stroke.strokeWidth = 2 * u
        when {
            !model.loaded -> text(c, "Loading…", 240f, 300f, 30f, u, align = Paint.Align.CENTER)
            model.view == GView.FOLDERS -> folders(c, u)
            n == 0 -> {
                text(c, "No pictures yet", 240f, 280f, 30f, u, align = Paint.Align.CENTER)
                text(c, "Take one with the camera", 240f, 322f, 22f, u, align = Paint.Align.CENTER)
            }
            model.view == GView.VIEWER -> viewer(c, u)
            else -> grid(c, u)
        }
        c.drawLine(0f, 576 * u, width.toFloat(), 576 * u, stroke)
        val left = if (model.loaded) model.leftLabel else ""
        if (left.isNotEmpty()) { arrow(c, 22f, 606f, -1, u); text(c, left, 46f, 614f, 30f, u, bold = true) }
        arrow(c, 458f, 606f, 1, u); text(c, model.rightLabel, 434f, 614f, 30f, u, bold = true, align = Paint.Align.RIGHT)
    }

    private fun grid(c: Canvas, u: Float) {
        val items = model.items
        for (r in 0 until model.visibleRows) for (col in 0 until model.cols) {
            val i = (model.top + r) * model.cols + col
            if (i >= items.size) break
            val x = LEFT + col * (TILE + GAP); val y = TOP + r * (TILE + GAP)
            val dst = RectF(x * u, y * u, (x + TILE) * u, (y + TILE) * u)
            val b = thumb(items[i])
            if (b != null) {
                if (model.colour) {
                    val s = minOf(b.width, b.height)
                    c.drawBitmap(b, Rect((b.width - s) / 2, (b.height - s) / 2, (b.width + s) / 2, (b.height + s) / 2), dst, smooth)
                } else c.drawBitmap(b, null, dst, chunky)
            } else { stroke.strokeWidth = 1 * u; c.drawRect(dst, stroke) }
            if (items[i].video) playBadge(c, x + 6, y + TILE - 34, 40f, 28f, u)
            if (i == model.sel) { stroke.strokeWidth = 4 * u; c.drawRect((x - 3) * u, (y - 3) * u, (x + TILE + 3) * u, (y + TILE + 3) * u, stroke) }
        }
        model.current?.let { text(c, fit((if (it.video) "Video ${GalleryData.clock(it.seconds)}  " else "") + it.name, 22f, 456f, u), 240f, 560f, 22f, u, align = Paint.Align.CENTER) }
    }

    private fun viewer(c: Canvas, u: Float) {
        stroke.strokeWidth = 2 * u
        c.drawRect((BOX_L - 3) * u, (BOX_T - 3) * u, (BOX_L + BOX_W + 3) * u, (BOX_T + BOX_H + 3) * u, stroke)
        val b = big
        if (b != null) {
            val s = minOf(BOX_W / b.width, BOX_H / b.height)
            val dw = b.width * s; val dh = b.height * s
            c.drawBitmap(b, null, RectF((BOX_L + (BOX_W - dw) / 2) * u, (BOX_T + (BOX_H - dh) / 2) * u, (BOX_L + (BOX_W + dw) / 2) * u, (BOX_T + (BOX_H + dh) / 2) * u), if (model.colour) smooth else chunky)
        }
        val it = model.current
        if (it != null && it.video) playBadge(c, 240f - 30f, BOX_T + BOX_H / 2 - 22f, 60f, 44f, u)
        notice?.let { c.drawRect(60 * u, 250 * u, 420 * u, 310 * u, fill); text(c, it, 240f, 292f, 30f, u, bold = true, align = Paint.Align.CENTER, color = lcd) }
        if (it != null) text(c, fit((if (it.video) "Video ${GalleryData.clock(it.seconds)}  " else "") + it.name, 22f, 456f, u), 240f, 566f, 22f, u, align = Paint.Align.CENTER)
    }

    private fun folders(c: Canvas, u: Float) {
        val rowH = 52f; val visible = 9
        val first = (model.folderSel - (visible - 1)).coerceAtLeast(0)
        for (k in 0 until visible) {
            val i = first + k
            if (i >= model.albums.size) break
            val y = 62f + k * rowH
            val a = model.albums[i]
            val on = i == model.folderSel
            if (on) c.drawRect(0f, y * u, width.toFloat(), (y + rowH - 4) * u, fill)
            val col = if (on) lcd else ink
            text(c, fit(a.name, 28f, 340f, u), 20f, y + 36f, 28f, u, bold = on, color = col)
            text(c, a.items.size.toString(), 460f, y + 36f, 28f, u, align = Paint.Align.RIGHT, color = col)
        }
    }

    /** A small box with a play triangle, so a video is told from a photo. */
    private fun playBadge(c: Canvas, x: Float, y: Float, w: Float, h: Float, u: Float) {
        fill.color = ink; c.drawRect(x * u, y * u, (x + w) * u, (y + h) * u, fill)
        fill.color = lcd
        val t = Path().apply { moveTo((x + w * 0.38f) * u, (y + h * 0.2f) * u); lineTo((x + w * 0.38f) * u, (y + h * 0.8f) * u); lineTo((x + w * 0.68f) * u, (y + h * 0.5f) * u); close() }
        c.drawPath(t, fill); fill.color = ink
    }

    private fun arrow(c: Canvas, x: Float, y: Float, dir: Int, u: Float) {
        val path = Path().apply { moveTo((x + 10 * dir) * u, y * u); lineTo((x - 10 * dir) * u, (y - 12) * u); lineTo((x - 10 * dir) * u, (y + 12) * u); close() }
        c.drawPath(path, fill)
    }
}
