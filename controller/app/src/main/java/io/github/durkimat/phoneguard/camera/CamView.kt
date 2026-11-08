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
    companion object {
        const val CELL = 3f
        /** The picture box on the 480 by 640 design grid is 105 by 140 cells of 3 pixels. */
        const val BOX_COLS = 105
        const val BOX_ROWS = 140
    }

    /** The picture box: left, top, width, height. */
    fun frame(): FloatArray { val w = BOX_COLS * CELL; val h = BOX_ROWS * CELL; return floatArrayOf((480f - w) / 2, 64f, w, h) }

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
    /** Quarter turns clockwise to draw the live picture with so it looks upright to whoever holds the phone (the screen itself never turns). */
    @Volatile private var turn = 0
    @Volatile private var smoothPic = false
    /** The colour photo, drawn smooth, when the gallery is asked for colour. */
    @Volatile private var colourPic: Bitmap? = null
    @Volatile var blink = false

    init { setOnTouchListener { _, _ -> true } /* the camera is a keypad screen */ }

    /** [degrees]: 0, 90, 180 or 270 clockwise; [smooth] draws a high-resolution picture with filtering instead of chunky cells. */
    fun show(b: Bitmap?, colour: Bitmap? = null, degrees: Int = 0, smooth: Boolean = false) { pic = b; colourPic = colour; turn = degrees; smoothPic = smooth; postInvalidate() }

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
        }.let { if (model.help) "Camera help" else it }
        text(c, title, 240f, 36f, 30f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
        if (model.recording && blink) { fill.color = lcd; c.drawCircle(40 * u, 26 * u, 9 * u, fill); fill.color = ink }
        // the picture, centred in its frame
        val f = frame(); val left = f[0]; val top = f[1]; val w = f[2]; val h = f[3]
        stroke.strokeWidth = 2 * u
        if (!model.help) c.drawRect((left - 3) * u, (top - 3) * u, (left + w + 3) * u, (top + h + 3) * u, stroke)
        val colour = colourPic
        val b = pic
        if (model.help) {
            model.helpLines.forEachIndexed { n, line -> text(c, line, 24f, 100f + n * 33f, 22f, u) }
        } else if (model.inGallery && model.colour && colour != null) {
            val s = minOf(w / colour.width, h / colour.height)
            val dw = colour.width * s; val dh = colour.height * s
            c.drawBitmap(colour, null, RectF((left + (w - dw) / 2) * u, (top + (h - dh) / 2) * u, (left + (w + dw) / 2) * u, (top + (h + dh) / 2) * u), smooth)
        } else if (b != null) {
            // turned a quarter, a wide picture becomes a tall one in the box
            val sideways = (turn == 90 || turn == 270) && !model.inGallery
            val bw = if (sideways) b.height else b.width; val bh = if (sideways) b.width else b.height
            val s = minOf(w / bw, h / bh)
            val dw = b.width * s; val dh = b.height * s
            c.save()
            c.translate((left + w / 2) * u, (top + h / 2) * u)
            if (!model.inGallery) c.rotate(turn.toFloat())
            c.drawBitmap(b, null, RectF(-dw / 2 * u, -dh / 2 * u, dw / 2 * u, dh / 2 * u), if (smoothPic) smooth else bmpPaint)
            c.restore()
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
        if (model.help) {} else if (model.inGallery) model.current?.let { text(c, (if (it.video) "Video  " else "") + it.name, 240f, 560f, 22f, u, align = Paint.Align.CENTER) }
        else text(c, model.infoLine, 240f, 560f, 22f, u, align = Paint.Align.CENTER)
        // soft keys
        c.drawLine(0f, 576 * u, width.toFloat(), 576 * u, stroke)
        val left1 = if (model.inGallery) (if (model.colour) "Dots" else "Colour") else if (model.help) "" else if (model.recording) "Stop" else "Help"
        val right1 = "Back"
        if (left1.isNotEmpty()) { arrow(c, 22f, 606f, -1, u); text(c, left1, 46f, 614f, 30f, u, bold = true) }
        arrow(c, 458f, 606f, 1, u); text(c, right1, 434f, 614f, 30f, u, bold = true, align = Paint.Align.RIGHT)
    }

    private fun arrow(c: Canvas, x: Float, y: Float, dir: Int, u: Float) {
        val path = android.graphics.Path().apply {
            moveTo((x + 10 * dir) * u, y * u); lineTo((x - 10 * dir) * u, (y - 12) * u); lineTo((x - 10 * dir) * u, (y + 12) * u); close()
        }
        c.drawPath(path, fill)
    }
}
