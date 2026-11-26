package io.github.durkimat.phoneguard.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Draws the gallery in the launcher's theme and font on the 480 by 640 design grid: a title bar, then a grid of pictures, one picture,
 * or the list of folders, and two soft keys. Pictures come from [thumb] (the grid) and [big] (the viewer); both may be null while loading.
 */
class GalleryView(context: Context, private val model: GalleryModel, themeId: String?, fontId: String?) : LcdView(context, themeId, fontId) {
    companion object {
        const val TILE = 150f
        const val GAP = 6f
        const val LEFT = 9f
        const val TOP = 58f
        /** The viewer's picture box (design pixels) and its size in 3-pixel cells. */
        const val CELL = 3
        const val BOX_L = 6f; const val BOX_T = 58f; const val BOX_W = 468f; const val BOX_H = 480f
    }

    /** A grid tile's picture: a small dithered bitmap (drawn chunky) or a colour thumbnail (drawn smooth, cropped square). Set by the activity. */
    @Volatile var thumb: (GItem) -> Bitmap? = { null }
    /** The viewer's picture, dithered or colour (see [model]); null while loading. */
    @Volatile var big: Bitmap? = null
    /** An animated sticker, drawn (and moving) when the viewer is in colour. */
    var anim: Drawable? = null
        set(v) { field?.callback = null; field = v; v?.callback = this; postInvalidate() }

    override fun verifyDrawable(who: Drawable): Boolean = who === anim || super.verifyDrawable(who)

    /** Shown over the viewer for a moment ("Cannot open"). */
    @Volatile var notice: String? = null

    override fun onDraw(c: Canvas) {
        val u = width / 480f
        val n = model.items.size
        val title = if (model.view == GView.FOLDERS) "Folders" else fit(model.album.name, 30f, 300f, u) + model.viewerTitle
        background(c, title, u)
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
        softKeys(c, if (model.loaded) model.leftLabel else "", model.rightLabel, u)
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
        val moving = anim
        if (model.colour && moving != null && moving.intrinsicWidth > 0) {
            val s = minOf(BOX_W / moving.intrinsicWidth, BOX_H / moving.intrinsicHeight)
            val dw = moving.intrinsicWidth * s; val dh = moving.intrinsicHeight * s
            moving.setBounds(((BOX_L + (BOX_W - dw) / 2) * u).toInt(), ((BOX_T + (BOX_H - dh) / 2) * u).toInt(), ((BOX_L + (BOX_W + dw) / 2) * u).toInt(), ((BOX_T + (BOX_H + dh) / 2) * u).toInt())
            moving.draw(c)
        } else if (b != null) {
            val s = minOf(BOX_W / b.width, BOX_H / b.height)
            val dw = b.width * s; val dh = b.height * s
            c.drawBitmap(b, null, RectF((BOX_L + (BOX_W - dw) / 2) * u, (BOX_T + (BOX_H - dh) / 2) * u, (BOX_L + (BOX_W + dw) / 2) * u, (BOX_T + (BOX_H + dh) / 2) * u), if (model.colour) smooth else chunky)
        }
        val it = model.current
        if (it != null && it.video) playBadge(c, 240f - 30f, BOX_T + BOX_H / 2 - 22f, 60f, 44f, u)
        notice?.let { c.drawRect(60 * u, 250 * u, 420 * u, 310 * u, fill); text(c, it, 240f, 292f, 30f, u, bold = true, align = Paint.Align.CENTER, color = lcd) }
        if (it != null) text(c, fit((if (it.video && !it.loops) "Video ${GalleryData.clock(it.seconds)}  " else "") + it.name, 22f, 456f, u), 240f, 566f, 22f, u, align = Paint.Align.CENTER)
    }

    private fun folders(c: Canvas, u: Float) = countedList(c, model.albums.map { it.name to it.items.size }, model.folderSel, u)

    /** A small box with a play triangle, so a video is told from a photo. */
    private fun playBadge(c: Canvas, x: Float, y: Float, w: Float, h: Float, u: Float) {
        fill.color = ink; c.drawRect(x * u, y * u, (x + w) * u, (y + h) * u, fill)
        fill.color = lcd
        val t = Path().apply { moveTo((x + w * 0.38f) * u, (y + h * 0.2f) * u); lineTo((x + w * 0.38f) * u, (y + h * 0.8f) * u); lineTo((x + w * 0.68f) * u, (y + h * 0.5f) * u); close() }
        c.drawPath(t, fill); fill.color = ink
    }
}
