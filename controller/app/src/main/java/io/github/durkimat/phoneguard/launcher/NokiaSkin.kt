package io.github.durkimat.phoneguard.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The first skin: a monochrome list-style Nokia. It only draws. Everything is laid out on a 480 by 640
 * design grid and scaled to the real screen, so another skin can use a different grid or none.
 */
class NokiaSkin : LauncherSkin {
    override val id = "nokia-list"

    private var model: LauncherModel? = null
    private var info: HomeInfo? = null
    private var view: Canvas2? = null

    override fun create(context: Context): View = Canvas2(context).also { view = it }

    override fun render(model: LauncherModel, info: HomeInfo) {
        this.model = model; this.info = info
        model.wrapper = { view?.wrap(it) ?: listOf(it) }
        model.pageLines = 11
        view?.invalidate()
    }

    private inner class Canvas2(context: Context) : View(context) {
        private val typefaces = HashMap<String, Typeface>()
        private var sizeScale = 1f

        private fun load(id: String): Typeface = try {
            when (id) {
                "sans" -> Typeface.SANS_SERIF
                "vt323" -> Typeface.createFromAsset(context.assets, "fonts/VT323-Regular.ttf")
                "pixelify" -> Typeface.createFromAsset(context.assets, "fonts/PixelifySans.ttf")
                else -> Typeface.MONOSPACE
            }
        } catch (_: Exception) { Typeface.MONOSPACE }

        private var lcd = 0
        private var ink = 0
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
        private val fill = Paint()
        private val stroke = Paint().apply { style = Paint.Style.STROKE }

        init {
            isFocusable = false
            setOnTouchListener { _, _ -> true } // a Nokia has no touchscreen
        }

        override fun onDraw(c: Canvas) {
            val m = model ?: return
            val i = info ?: return
            val u = width / 480f
            val t = Themes.byId(m.theme)
            lcd = t.lcd; ink = t.ink; fill.color = ink; stroke.color = ink
            val f = Fonts.byId(m.font).id
            p.typeface = typefaces.getOrPut(f) { load(f) }
            sizeScale = if (f == "vt323") 1.3f else 1f
            c.drawColor(lcd)
            statusLine(c, i, u)
            when (m.screen) {
                Screen.HOME -> home(c, i, u)
                Screen.THREAD -> thread(c, m, u)
                Screen.WRITE -> write(c, m, u)
                else -> menu(c, m, u)
            }
            m.notice?.let { notice(c, it, u) }
        }

        private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, u: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT, color: Int = ink) {
            p.textSize = size * sizeScale * u; p.isFakeBoldText = bold; p.textAlign = align; p.color = color
            c.drawText(s, x * u, y * u, p)
        }

        private fun statusLine(c: Canvas, i: HomeInfo, u: Float) {
            if (i.signalBars < 0) text(c, "No SIM", 14f, 32f, 24f, u)
            else for (b in 0 until 4) {
                val h = 8f + 7f * b
                val r = RectF((14 + b * 12) * u, (36 - h) * u, (14 + b * 12 + 8) * u, 36 * u)
                if (b < i.signalBars) c.drawRect(r, fill) else { stroke.strokeWidth = 2 * u; c.drawRect(r, stroke) }
            }
            // battery: outline, level, nub
            val bx = 480f - 14 - 56
            stroke.strokeWidth = 3 * u
            c.drawRect(bx * u, 10 * u, (bx + 52) * u, 36 * u, stroke)
            c.drawRect((bx + 52) * u, 17 * u, (bx + 56) * u, 29 * u, fill)
            c.drawRect((bx + 5) * u, 15 * u, (bx + 5 + 42f * i.batteryPct / 100f) * u, 31 * u, fill)
            text(c, (if (i.charging) "+" else "") + i.batteryPct + "%", bx - 8, 33f, 24f, u, align = Paint.Align.RIGHT)
            stroke.strokeWidth = 2 * u
            c.drawLine(0f, 46 * u, width.toFloat(), 46 * u, stroke)
        }

        // The two labels are the D-pad's Left and Right: a small arrow shows which way to press.
        private fun softKeys(c: Canvas, left: String, right: String, u: Float) {
            stroke.strokeWidth = 2 * u
            c.drawLine(0f, (640 - 64) * u, width.toFloat(), (640 - 64) * u, stroke)
            val cy = 606f
            if (left.isNotEmpty()) {
                arrow(c, 22f, cy, -1, u)
                text(c, left, 46f, 614f, 30f, u, bold = true)
            }
            if (right.isNotEmpty()) {
                arrow(c, 458f, cy, 1, u)
                text(c, right, 434f, 614f, 30f, u, bold = true, align = Paint.Align.RIGHT)
            }
        }

        private fun arrow(c: Canvas, x: Float, y: Float, dir: Int, u: Float) {
            val path = android.graphics.Path().apply {
                moveTo((x + 10 * dir) * u, y * u); lineTo((x - 10 * dir) * u, (y - 12) * u); lineTo((x - 10 * dir) * u, (y + 12) * u); close()
            }
            c.drawPath(path, fill)
        }

        private fun home(c: Canvas, i: HomeInfo, u: Float) {
            // deliberately no mode name or banner: in School this must look like any plain phone
            text(c, i.time, 240f, 270f, 120f, u, bold = true, align = Paint.Align.CENTER)
            text(c, i.date, 240f, 332f, 36f, u, align = Paint.Align.CENTER)
            var y = 450f
            if (i.missedCalls > 0) { text(c, plural(i.missedCalls, "missed call"), 64f, y, 28f, u); y += 40f }
            if (i.newMessages > 0) text(c, plural(i.newMessages, "new message"), 64f, y, 28f, u)
            softKeys(c, i.softLeft, i.softRight, u)
        }

        private fun menu(c: Canvas, m: LauncherModel, u: Float) {
            // title bar
            c.drawRect(0f, 47 * u, width.toFloat(), 99 * u, fill)
            text(c, m.title, 240f, 85f, 30f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
            val rowH = 62f
            val visible = ((640f - 64 - 100) / rowH).toInt().coerceAtLeast(1)
            val first = (m.selected - visible + 1).coerceAtLeast(0).coerceAtMost((m.rows.size - visible).coerceAtLeast(0))
            for (n in 0 until visible) {
                val idx = first + n
                val row = m.rows.getOrNull(idx) ?: break
                val top = 104f + n * rowH
                val label = (if (idx < 9) "${idx + 1}  " else "   ") + row
                if (idx == m.selected) {
                    c.drawRect(0f, top * u, width.toFloat(), (top + rowH - 4) * u, fill)
                    text(c, label, 20f, top + 42f, 32f, u, bold = true, color = lcd)
                } else text(c, label, 20f, top + 42f, 32f, u)
            }
            if (m.rows.isEmpty()) text(c, "Empty", 240f, 300f, 30f, u, align = Paint.Align.CENTER)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private val BODY = 28f

        /** Splits text into lines that fit the screen width in the current font. Called by the model. */
        fun wrap(text: String): List<String> {
            val mp = Paint(Paint.ANTI_ALIAS_FLAG)
            val f = Fonts.byId(model?.font).id
            mp.typeface = typefaces.getOrPut(f) { load(f) }
            mp.textSize = BODY * (if (f == "vt323") 1.3f else 1f)
            val max = 440f
            val out = ArrayList<String>()
            for (para in text.split('\n')) {
                var line = ""
                for (word in para.split(' ')) {
                    var w = word
                    val trial = if (line.isEmpty()) w else "$line $w"
                    if (mp.measureText(trial) <= max) { line = trial; continue }
                    if (line.isNotEmpty()) { out.add(line); line = "" }
                    while (mp.measureText(w) > max) { // a word longer than the screen
                        val n = mp.breakText(w, true, max, null).coerceAtLeast(1)
                        out.add(w.substring(0, n)); w = w.substring(n)
                    }
                    line = w
                }
                out.add(line)
            }
            return out
        }

        private fun thread(c: Canvas, m: LauncherModel, u: Float) {
            val convo = m.openConvo ?: return
            val msg = m.shownMsg ?: return
            c.drawRect(0f, 47 * u, width.toFloat(), 99 * u, fill)
            text(c, m.title, 240f, 85f, 30f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
            val lines = wrap(msg.body)
            val whenText = SimpleDateFormat(if (sameDay(msg.date)) "HH:mm" else "d MMM HH:mm", Locale.UK).format(Date(msg.date))
            text(c, (if (msg.mine) "Me" else convo.name.take(12)) + "  " + whenText, 16f, 134f, 24f, u, bold = true)
            text(c, "${m.selected + 1}/${convo.msgs.size}", 464f, 134f, 24f, u, align = Paint.Align.RIGHT)
            val page = lines.drop(m.scroll).take(m.pageLines)
            page.forEachIndexed { n, s -> text(c, s, 16f, 176f + n * 36f, BODY, u) }
            if (m.scroll > 0) arrowV(c, 456f, 150f, -1, u)
            if (m.scroll + m.pageLines < lines.size) arrowV(c, 456f, 548f, 1, u)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun sameDay(t: Long): Boolean {
            val f = SimpleDateFormat("yyyyMMdd", Locale.UK)
            return f.format(Date(t)) == f.format(Date(io.github.durkimat.phoneguard.Clock.now()))
        }

        private fun arrowV(c: Canvas, x: Float, y: Float, dir: Int, u: Float) {
            val path = android.graphics.Path().apply {
                moveTo(x * u, (y + 9 * dir) * u); lineTo((x - 10) * u, (y - 9 * dir) * u); lineTo((x + 10) * u, (y - 9 * dir) * u); close()
            }
            c.drawPath(path, fill)
        }

        private fun write(c: Canvas, m: LauncherModel, u: Float) {
            // the To field takes the title bar's place, inverted while it has focus
            if (m.writeField == 0) {
                c.drawRect(0f, 47 * u, width.toFloat(), 99 * u, fill)
                text(c, "To: " + m.writeTo + "_", 16f, 85f, 30f, u, bold = true, color = lcd)
            } else {
                text(c, "To: " + m.writeTo, 16f, 85f, 30f, u, bold = true)
                stroke.strokeWidth = 2 * u; c.drawLine(0f, 99 * u, width.toFloat(), 99 * u, stroke)
            }
            val shown = if (m.writeField == 1) m.writeText.substring(0, m.cursor) + "_" + m.writeText.substring(m.cursor) else m.writeText
            val lines = wrap(shown)
            val first = (lines.size - 11).coerceAtLeast(0) // keep the end, where the cursor usually is
            lines.drop(first).take(11).forEachIndexed { n, s -> text(c, s, 16f, 140f + n * 36f, BODY, u) }
            text(c, m.modeName, 16f, 560f, 24f, u, bold = true)
            text(c, "" + m.writeText.length, 464f, 560f, 24f, u, align = Paint.Align.RIGHT)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun notice(c: Canvas, s: String, u: Float) {
            c.drawRect(30 * u, 250 * u, 450 * u, 350 * u, fill)
            text(c, s, 240f, 312f, 32f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
        }

        private fun plural(n: Int, what: String) = "$n $what" + if (n == 1) "" else "s"
    }
}
