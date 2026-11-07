package io.github.durkimat.phoneguard.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

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
        this.model = model; this.info = info; view?.invalidate()
    }

    private inner class Canvas2(context: Context) : View(context) {
        private val lcd = 0xFFCFD3CC.toInt()
        private val ink = 0xFF1A1A1A.toInt()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; color = ink }
        private val fill = Paint().apply { color = ink }
        private val stroke = Paint().apply { color = ink; style = Paint.Style.STROKE }

        init {
            isFocusable = false
            setOnTouchListener { _, _ -> true } // a Nokia has no touchscreen
        }

        override fun onDraw(c: Canvas) {
            val m = model ?: return
            val i = info ?: return
            val u = width / 480f
            c.drawColor(lcd)
            statusLine(c, i, u)
            if (m.screen == Screen.HOME) home(c, i, u) else menu(c, m, i, u)
        }

        private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, u: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT, color: Int = ink) {
            p.textSize = size * u; p.isFakeBoldText = bold; p.textAlign = align; p.color = color
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

        private fun menu(c: Canvas, m: LauncherModel, i: HomeInfo, u: Float) {
            // title bar
            c.drawRect(0f, 47 * u, width.toFloat(), 99 * u, fill)
            text(c, "Menu", 240f, 85f, 30f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
            val rowH = 62f
            val visible = ((640f - 64 - 100) / rowH).toInt().coerceAtLeast(1)
            val first = (m.selected - visible + 1).coerceAtLeast(0).coerceAtMost((m.items.size - visible).coerceAtLeast(0))
            for (n in 0 until visible) {
                val idx = first + n
                val item = m.items.getOrNull(idx) ?: break
                val top = 104f + n * rowH
                val label = (if (idx < 9) "${idx + 1}  " else "   ") + item.label
                if (idx == m.selected) {
                    c.drawRect(0f, top * u, width.toFloat(), (top + rowH - 4) * u, fill)
                    text(c, label, 20f, top + 42f, 32f, u, bold = true, color = lcd)
                } else text(c, label, 20f, top + 42f, 32f, u)
            }
            if (m.items.isEmpty()) text(c, "No apps", 240f, 300f, 30f, u, align = Paint.Align.CENTER)
            softKeys(c, "Select", "Back", u)
        }

        private fun plural(n: Int, what: String) = "$n $what" + if (n == 1) "" else "s"
    }
}
