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
    companion object { const val COLS = 105; const val ROWS = 140; const val CELL = 3f }

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
        val u = width / 480f
        c.drawColor(lcd)
        // title bar
        c.drawRect(0f, 0f, width.toFloat(), 52 * u, fill)
        val title = when {
            model.inGallery -> "Gallery" + if (model.items.isNotEmpty()) "  ${model.index + 1}/${model.items.size}" else ""
            model.recording -> "REC %02d:%02d".format(model.recordingSeconds / 60, model.recordingSeconds % 60)
            model.mode == CamMode.VIDEO -> "Video"
            else -> "Photo"
        }
        text(c, title, 240f, 36f, 30f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
        if (model.recording && blink) { fill.color = lcd; c.drawCircle(40 * u, 26 * u, 9 * u, fill); fill.color = ink }
        // the picture, centred in its frame
        val w = COLS * CELL; val h = ROWS * CELL
        val left = (480f - w) / 2; val top = 64f
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
        } else if (model.inGallery && model.items.isEmpty()) text(c, "No photos yet", 240f, 280f, 30f, u, align = Paint.Align.CENTER)
        // self-timer count
        if (model.countdown > 0) {
            c.drawRect(190 * u, 210 * u, 290 * u, 320 * u, fill)
            text(c, model.countdown.toString(), 240f, 300f, 90f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
        }
        model.notice?.let {
            c.drawRect(60 * u, 250 * u, 420 * u, 310 * u, fill)
            text(c, it, 240f, 292f, 30f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
        }
        if (model.inGallery) model.current?.let { text(c, (if (it.video) "Video  " else "") + it.name, 240f, 560f, 22f, u, align = Paint.Align.CENTER) }
        else text(c, model.infoLine, 240f, 560f, 22f, u, align = Paint.Align.CENTER)
        // soft keys
        c.drawLine(0f, 576 * u, width.toFloat(), 576 * u, stroke)
        val left1 = if (model.inGallery) (if (model.colour) "Dots" else "Colour") else if (model.mode == CamMode.VIDEO) (if (model.recording) "Stop" else "Record") else "Shoot"
        val right1 = "Back"
        arrow(c, 22f, 606f, -1, u); text(c, left1, 46f, 614f, 30f, u, bold = true)
        arrow(c, 458f, 606f, 1, u); text(c, right1, 434f, 614f, 30f, u, bold = true, align = Paint.Align.RIGHT)
    }

    private fun arrow(c: Canvas, x: Float, y: Float, dir: Int, u: Float) {
        val path = android.graphics.Path().apply {
            moveTo((x + 10 * dir) * u, y * u); lineTo((x - 10 * dir) * u, (y - 12) * u); lineTo((x - 10 * dir) * u, (y + 12) * u); close()
        }
        c.drawPath(path, fill)
    }
}
