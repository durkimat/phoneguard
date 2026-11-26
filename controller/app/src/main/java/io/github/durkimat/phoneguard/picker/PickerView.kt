package io.github.durkimat.phoneguard.picker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import io.github.durkimat.phoneguard.gallery.LcdView

/** Draws the pickers: a grid of emoji or stickers, the list of groups, or one sticker large before it is sent. Stickers are in colour, as they will arrive. */
class PickerView(context: Context, private val model: PickerModel, themeId: String?, fontId: String?) : LcdView(context, themeId, fontId) {
    /** A sticker's small picture, or null while it loads. Set by the activity. */
    var thumb: (PickItem) -> Bitmap? = { null }
    /** The sticker being previewed (it moves if it is animated). */
    var preview: Drawable? = null
        set(v) { field?.callback = null; field = v; v?.callback = this; postInvalidate() }

    override fun verifyDrawable(who: Drawable): Boolean = who === preview || super.verifyDrawable(who)

    override fun onDraw(c: Canvas) {
        val u = width / 480f
        val n = model.group.items.size
        background(c, if (model.view == PView.GROUPS) "Groups" else fit(model.group.name, 30f, 300f, u) + if (n > 0) "  ${model.position}/$n" else "", u)
        when (model.view) {
            PView.GROUPS -> countedList(c, model.groups.map { it.name to it.items.size }, model.groupSel, u)
            PView.PREVIEW -> drawPreview(c, u)
            PView.GRID -> grid(c, u)
        }
        softKeys(c, model.leftLabel, "Back", u)
    }

    private fun grid(c: Canvas, u: Float) {
        val tile = 462f / model.cols
        val emoji = model.kind == PickKind.EMOJI
        for (r in 0 until model.visibleRows) if (r > 0 && model.startsGroup(model.top + r)) { // a line between one group and the next
            stroke.strokeWidth = 2 * u; c.drawLine(9f * u, (60f + r * tile - 1f) * u, 471f * u, (60f + r * tile - 1f) * u, stroke)
        }
        for (r in 0 until model.visibleRows) for (col in 0 until model.cols) {
            val i = (model.top + r) * model.cols + col
            val item = model.cells.getOrNull(i) ?: continue
            val x = 9f + col * tile; val y = 60f + r * tile
            if (emoji) text(c, item.value, x + tile / 2, y + tile * 0.7f, tile * 0.56f, u, align = Paint.Align.CENTER)
            else {
                val b = thumb(item)
                if (b != null) c.drawBitmap(b, null, RectF((x + 8) * u, (y + 8) * u, (x + tile - 8) * u, (y + tile - 8) * u), smooth)
                else { stroke.strokeWidth = 1 * u; c.drawRect((x + 20) * u, (y + 20) * u, (x + tile - 20) * u, (y + tile - 20) * u, stroke) }
            }
            if (i == model.sel) { stroke.strokeWidth = 4 * u; c.drawRect((x + 2) * u, (y + 2) * u, (x + tile - 2) * u, (y + tile - 2) * u, stroke) }
        }
        if (!emoji) model.current?.let { text(c, fit(it.name, 22f, 456f, u), 240f, 560f, 22f, u, align = Paint.Align.CENTER) }
    }

    private fun drawPreview(c: Canvas, u: Float) {
        val d = preview
        if (d != null) { d.setBounds((60 * u).toInt(), (110 * u).toInt(), (420 * u).toInt(), (470 * u).toInt()); d.draw(c) }
        model.current?.let { text(c, fit(it.name, 24f, 456f, u), 240f, 530f, 24f, u, align = Paint.Align.CENTER) }
    }
}
