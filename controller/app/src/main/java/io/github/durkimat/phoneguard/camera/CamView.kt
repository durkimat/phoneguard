package io.github.durkimat.phoneguard.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import io.github.durkimat.phoneguard.launcher.Fonts
import io.github.durkimat.phoneguard.launcher.Themes

/** Draws the camera screens in the launcher's theme and font: a title bar, the picture, a settings line and two soft keys. */
class CamView(context: Context, private val model: CameraModel, themeId: String?, fontId: String?) : View(context) {
    companion object { const val CELL = 3f }

    /** Held sideways the screen is wider than tall: the layout is drawn on a 640 by 480 grid and the picture is 140 by 105 cells. */
    @Volatile var land = false
        private set
    val cols: Int get() = if (land) 140 else 105
    val rows: Int get() = if (land) 105 else 140
    /** The picture frame on the design grid: left, top, width, height. */
    fun frame(): FloatArray {
        val w = cols * CELL; val h = rows * CELL
        return if (land) floatArrayOf((640f - w) / 2, 48f, w, h) else floatArrayOf((480f - w) / 2, 64f, w, h)
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
    private val bmpPaint = Paint().apply { isFilterBitmap = false }
    private val smooth = Paint().apply { isFilterBitmap = true }

    /** The live (or gallery) picture, already turned into theme colours; set from any thread through [show]. */
    @Volatile private var pic: Bitmap? = null
    /** The colour photo, drawn smooth, when the gallery is asked for colour. */
    @Volatile private var colourPic: Bitmap? = null
    @Volatile var blink = false

    init { setOnTouchListener { _, _ -> true } /* the camera is a keypad screen */ }

    fun show(b: Bitmap?, colour: Bitmap? = null) { pic = b; colourPic = colour; postInvalidate() }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, u: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT, color: Int = ink) {
        p.textSize = size * sizeScale * u; p.isFakeBoldText = bold; p.textAlign = align; p.color = color
        c.drawText(s, x * u, y * u, p)
    }

    override fun onDraw(c: Canvas) {
        land = width > height
        val gw = if (land) 640f else 480f
        val u = width / gw
        c.drawColor(lcd)
        // title bar
        val barH = if (land) 40f else 52f
        c.drawRect(0f, 0f, width.toFloat(), barH * u, fill)
        val title = when {
            model.inGallery -> "Gallery" + if (model.items.isNotEmpty()) "  ${model.index + 1}/${model.items.size}" else ""
            model.recording -> "REC %02d:%02d".format(model.recordingSeconds / 60, model.recordingSeconds % 60)
            model.mode == CamMode.VIDEO -> "Video"
            else -> "Photo"
        }
        text(c, title, gw / 2, barH - 12f, if (land) 26f else 30f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
        if (model.recording && blink) { fill.color = lcd; c.drawCircle(36 * u, barH / 2 * u, 8 * u, fill); fill.color = ink }
        // the picture, centred in its frame
        val f = frame(); val left = f[0]; val top = f[1]; val w = f[2]; val h = f[3]
        stroke.strokeWidth = 2 * u
        c.drawRect((left - 3) * u, (top - 3) * u, (left + w + 3) * u, (top + h + 3) * u, stroke)
        val colour = colourPic
        val b = pic
        if (model.inGallery && model.colour && colour != null) {
            val s = minOf(w / colour.width, h / colour.height)
            val dw = colour.width * s; val dh = colour.height * s
            c.drawBitmap(colour, null, RectF((left + (w - dw) / 2) * u, (top + (h - dh) / 2) * u, (left + (w + dw) / 2) * u, (top + (h + dh) / 2) * u), smooth)
        } else if (b != null) {
            val dw = b.width * CELL; val dh = b.height * CELL
            c.drawBitmap(b, null, RectF((left + (w - dw) / 2) * u, (top + (h - dh) / 2) * u, (left + (w + dw) / 2) * u, (top + (h + dh) / 2) * u), bmpPaint)
        } else if (model.inGallery && model.items.isEmpty()) text(c, "No photos yet", gw / 2, top + h / 2, 30f, u, align = Paint.Align.CENTER)
        val midY = top + h / 2
        // self-timer count
        if (model.countdown > 0) {
            c.drawRect((gw / 2 - 50) * u, (midY - 55) * u, (gw / 2 + 50) * u, (midY + 55) * u, fill)
            text(c, model.countdown.toString(), gw / 2, midY + 35, 90f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
        }
        model.notice?.let {
            c.drawRect(60 * u, (midY - 30) * u, (gw - 60) * u, (midY + 30) * u, fill)
            text(c, it, gw / 2, midY + 12, 30f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
        }
        val infoY = top + h + (if (land) 30f else 22f) + 8f
        if (model.inGallery) model.current?.let { text(c, (if (it.video) "Video  " else "") + it.name, gw / 2, infoY, 22f, u, align = Paint.Align.CENTER) }
        else text(c, model.infoLine, gw / 2, infoY, 22f, u, align = Paint.Align.CENTER)
        // soft keys
        val keyLine = (if (land) 424f else 576f)
        c.drawLine(0f, keyLine * u, width.toFloat(), keyLine * u, stroke)
        val left1 = if (model.inGallery) (if (model.colour) "Dots" else "Colour") else if (model.mode == CamMode.VIDEO) (if (model.recording) "Stop" else "Record") else "Shoot"
        val right1 = "Back"
        val cy = keyLine + (if (land) 30f else 30f)
        arrow(c, 22f, cy, -1, u); text(c, left1, 46f, cy + 8f, 28f, u, bold = true)
        arrow(c, gw - 22f, cy, 1, u); text(c, right1, gw - 46f, cy + 8f, 28f, u, bold = true, align = Paint.Align.RIGHT)
    }

    private fun arrow(c: Canvas, x: Float, y: Float, dir: Int, u: Float) {
        val path = android.graphics.Path().apply {
            moveTo((x + 10 * dir) * u, y * u); lineTo((x - 10 * dir) * u, (y - 12) * u); lineTo((x - 10 * dir) * u, (y + 12) * u); close()
        }
        c.drawPath(path, fill)
    }
}
