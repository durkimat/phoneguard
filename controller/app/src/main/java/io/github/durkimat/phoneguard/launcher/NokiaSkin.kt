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
                Screen.CALC -> calc(c, m, u)
                Screen.DIAL -> dial(c, m, u)
                Screen.CALL -> callScreen(c, m, u)
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
            if (m.rows.isEmpty()) text(c, if (m.screen == Screen.WHATSAPP) "No chats yet" else "Empty", 240f, 300f, 30f, u, align = Paint.Align.CENTER)
            if (m.screen == Screen.WHATSAPP && m.waNote.isNotEmpty()) text(c, m.waNote, 240f, 556f, 26f, u, align = Paint.Align.CENTER)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private val BODY = 28f

        private val mp = Paint(Paint.ANTI_ALIAS_FLAG)
        private fun bodyPaint(): Paint {
            val f = Fonts.byId(model?.font).id
            mp.typeface = typefaces.getOrPut(f) { load(f) }
            mp.textSize = BODY * (if (f == "vt323") 1.3f else 1f)
            return mp
        }

        /** Where each screen line starts and ends in [text] (end is exclusive; wrapped-over spaces are left out). */
        fun spans(text: String): List<IntRange> {
            val paint = bodyPaint()
            val max = 440f
            val out = ArrayList<IntRange>()
            var i = 0
            while (true) {
                var j = i; var lastSpace = -1; var done = false
                while (j < text.length) {
                    val ch = text[j]
                    if (ch == '\n') { out.add(i until j); i = j + 1; done = true; break }
                    if (ch == ' ') lastSpace = j
                    if (paint.measureText(text, i, j + 1) > max) {
                        if (lastSpace > i) { out.add(i until lastSpace); i = lastSpace + 1 }
                        else { val end = if (j > i) j else j + 1; out.add(i until end); i = end }
                        done = true; break
                    }
                    j++
                }
                if (!done) { out.add(i until text.length); break }
            }
            return out
        }

        /** Splits text into lines that fit the screen width in the current font. Called by the model. */
        fun wrap(text: String): List<String> = spans(text).map { text.substring(it.first, it.last + 1) }

        private fun thread(c: Canvas, m: LauncherModel, u: Float) {
            val convo = m.openConvo ?: return
            val msg = m.shownMsg ?: return
            c.drawRect(0f, 47 * u, width.toFloat(), 99 * u, fill)
            text(c, m.title, 240f, 85f, 30f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
            val lines = wrap(msg.body)
            val whenText = SimpleDateFormat(if (sameDay(msg.date)) "HH:mm" else "d MMM HH:mm", Locale.UK).format(Date(msg.date))
            val pos = "${m.selected + 1}/${convo.msgs.size}"
            val who = if (msg.mine) "Me" else convo.name
            val room = 448f - measureBold(pos, 24f) - measureBold("  $whenText", 24f)
            text(c, fit(who, room, 24f) + "  " + whenText, 16f, 134f, 24f, u, bold = true)
            text(c, pos, 464f, 134f, 24f, u, align = Paint.Align.RIGHT)
            val page = lines.drop(m.scroll).take(m.pageLines)
            page.forEachIndexed { n, s -> text(c, s, 16f, 176f + n * 36f, BODY, u) }
            if (m.scroll > 0) arrowV(c, 456f, 150f, -1, u)
            if (m.scroll + m.pageLines < lines.size) arrowV(c, 456f, 548f, 1, u)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun measureBold(s: String, size: Float): Float {
            val paint = bodyPaint(); paint.textSize = size * (if (Fonts.byId(model?.font).id == "vt323") 1.3f else 1f); paint.isFakeBoldText = true
            val w = paint.measureText(s); paint.isFakeBoldText = false
            return w
        }

        /** Shortens a name to fit: names lose their end, numbers lose their front so the last digits stay visible. */
        private fun fit(s: String, room: Float, size: Float): String {
            if (measureBold(s, size) <= room) return s
            val number = s.firstOrNull()?.let { it.isDigit() || it == '+' } == true
            var t = s
            while (t.length > 1 && measureBold((if (number) "…$t" else "$t…"), size) > room) t = if (number) t.drop(1) else t.dropLast(1)
            return if (number) "…$t" else "$t…"
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
                text(c, "To: " + m.writeLabel.ifBlank { m.writeTo } + "_", 16f, 85f, 30f, u, bold = true, color = lcd)
            } else {
                text(c, "To: " + m.writeLabel.ifBlank { m.writeTo }, 16f, 85f, 30f, u, bold = true)
                stroke.strokeWidth = 2 * u; c.drawLine(0f, 99 * u, width.toFloat(), 99 * u, stroke)
            }
            val body = m.writeText
            val lines = spans(body)
            val comp = m.composingRange
            val paint = bodyPaint()
            val cursorLine = if (comp != null || m.writeField == 0) -1 else lines.indexOfFirst { it.first <= m.cursor && m.cursor <= it.last + 1 }.let { if (it < 0) lines.size - 1 else it }
            var first = (lines.size - 11).coerceAtLeast(0) // keep the end, where the cursor usually is
            if (cursorLine in 0 until first) first = cursorLine
            for (n in 0 until 11) {
                val idx = first + n
                val span = lines.getOrNull(idx) ?: break
                val y = 140f + n * 36f
                val line = body.substring(span.first, span.last + 1)
                text(c, line, 16f, y, BODY, u)
                if (comp != null && comp.first <= span.last + 1 && comp.last + 1 >= span.first) {
                    val from = maxOf(comp.first, span.first); val to = minOf(comp.last + 1, span.last + 1)
                    val x1 = paint.measureText(body, span.first, from); val x2 = paint.measureText(body, span.first, to)
                    c.drawRect((16 + x1) * u, (y + 6) * u, (16 + x2) * u, (y + 9) * u, fill)
                }
                if (idx == cursorLine) {
                    val x = 16 + paint.measureText(body, span.first, m.cursor.coerceIn(span.first, span.last + 1))
                    c.drawRect(x * u, (y - 24) * u, (x + 3) * u, (y + 7) * u, fill)
                }
            }
            if (lines.isEmpty() && m.writeField == 1) c.drawRect(16 * u, 116 * u, 19 * u, 147 * u, fill)
            text(c, m.modeName, 16f, 560f, 24f, u, bold = true)
            if (m.writeWa) text(c, "WhatsApp", 240f, 560f, 24f, u, align = Paint.Align.CENTER)
            text(c, "" + m.writeText.length, 464f, 560f, 24f, u, align = Paint.Align.RIGHT)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun calc(c: Canvas, m: LauncherModel, u: Float) {
            c.drawRect(0f, 47 * u, width.toFloat(), 99 * u, fill)
            text(c, m.title, 240f, 85f, 30f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
            text(c, m.calc.pending, 464f, 160f, 30f, u, align = Paint.Align.RIGHT)
            val big = m.calc.display
            var size = 84f
            val paint = bodyPaint()
            paint.textSize = size
            while (paint.measureText(big) > 440f && size > 30f) { size -= 4f; paint.textSize = size }
            text(c, big, 464f, 270f, size / (if (Fonts.byId(m.font).id == "vt323") 1.3f else 1f), u, bold = true, align = Paint.Align.RIGHT)
            stroke.strokeWidth = 2 * u; c.drawLine(16 * u, 296 * u, 464 * u, 296 * u, stroke)
            text(c, "*   + - x /", 16f, 360f, 26f, u)
            text(c, "#   decimal point", 16f, 400f, 26f, u)
            text(c, "Up/Down   + and -", 16f, 440f, 26f, u)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun dial(c: Canvas, m: LauncherModel, u: Float) {
            val n = m.dialNumber
            var size = 84f
            val paint = bodyPaint(); paint.textSize = size
            while (paint.measureText(n) > 440f && size > 30f) { size -= 4f; paint.textSize = size }
            text(c, n, 464f, 300f, size / (if (Fonts.byId(m.font).id == "vt323") 1.3f else 1f), u, bold = true, align = Paint.Align.RIGHT)
            m.dialName?.let { text(c, it, 464f, 350f, 30f, u, align = Paint.Align.RIGHT) }
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun callScreen(c: Canvas, m: LauncherModel, u: Float) {
            val call = m.call ?: return
            val who = call.name ?: call.number.ifBlank { "Unknown" }
            val scale = if (Fonts.byId(m.font).id == "vt323") 1.3f else 1f
            fun fitted(s: String, start: Float): Float {
                var size = start; val paint = bodyPaint(); paint.textSize = size * scale; paint.isFakeBoldText = true
                while (paint.measureText(s) > 440f && size > 24f) { size -= 2f; paint.textSize = size * scale }
                paint.isFakeBoldText = false
                return size
            }
            when (call.phase) {
                CallPhase.RINGING, CallPhase.DIALING -> {
                    text(c, if (call.phase == CallPhase.RINGING) "Incoming call" else "Calling...", 240f, 150f, 30f, u, align = Paint.Align.CENTER)
                    text(c, who, 240f, 280f, fitted(who, 52f), u, bold = true, align = Paint.Align.CENTER)
                    if (call.name != null) text(c, call.number, 240f, 335f, 30f, u, align = Paint.Align.CENTER)
                }
                CallPhase.ACTIVE -> {
                    text(c, who, 240f, 130f, fitted(who, 34f), u, bold = true, align = Paint.Align.CENTER)
                    val secs = ((io.github.durkimat.phoneguard.Clock.now() - call.startedAt) / 1000).toInt().coerceAtLeast(0)
                    text(c, clock(secs), 240f, 270f, 96f, u, bold = true, align = Paint.Align.CENTER)
                    if (call.name != null) text(c, call.number, 240f, 325f, 28f, u, align = Paint.Align.CENTER)
                    var y = 400f
                    if (call.muted) { text(c, "Muted", 240f, y, 30f, u, align = Paint.Align.CENTER); y += 44f }
                    if (call.speaker) text(c, "Speaker", 240f, y, 30f, u, align = Paint.Align.CENTER)
                }
                CallPhase.ENDED -> {
                    text(c, who, 240f, 130f, fitted(who, 34f), u, bold = true, align = Paint.Align.CENTER)
                    c.drawRect(60 * u, 230 * u, 420 * u, 370 * u, fill)
                    text(c, "Call ended", 240f, 292f, 36f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
                    text(c, clock(call.seconds), 240f, 342f, 36f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
                }
            }
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun clock(secs: Int): String = if (secs >= 3600) "%d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60) else "%02d:%02d".format(secs / 60, secs % 60)

        private fun notice(c: Canvas, s: String, u: Float) {
            c.drawRect(30 * u, 250 * u, 450 * u, 350 * u, fill)
            text(c, s, 240f, 312f, 32f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
        }

        private fun plural(n: Int, what: String) = "$n $what" + if (n == 1) "" else "s"
    }
}
