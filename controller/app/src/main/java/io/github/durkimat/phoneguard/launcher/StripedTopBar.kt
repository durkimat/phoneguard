package io.github.durkimat.phoneguard.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** What the top bar shows. Wi-Fi, Bluetooth and data: -1 not shown, 0 on but unused, 1 in use, 2 switched off. [time] null: no clock. */
data class BarState(
    val signalBars: Int, val data: Int, val wifi: Int, val bt: Int, val torch: Boolean, val alarm: Boolean, val timer: Boolean,
    val time: String?, val batteryPct: Int, val charging: Boolean,
    /** The left and right of the highlight box in the bar, if one is shown: the stripes clear it. */
    val chosen: Pair<Float, Float>? = null,
)

/**
 * The top bar of the Mac look with striped windows: the window title's stripes run between the symbols and wrap round their shapes
 * ([StripeWrap]). Every symbol has its own fixed place; the radios stay where every look has them. Working out the gaps costs some
 * time, so the finished bar is kept and drawn again only when something on it changes.
 */
internal class StripedTopBar(
    private val bell: (Canvas, Float, Float, Float, Float) -> Unit,
    private val stopwatch: (Canvas, Float, Float, Float, Float) -> Unit,
    private val battery: (Canvas, Int, Boolean, Float) -> Unit,
) {
    private var key: List<Any?>? = null
    private var kept: Bitmap? = null

    fun draw(pen: Pen, c: Canvas, s: BarState, font: String, u: Float) {
        val now = listOf<Any?>(s, font, pen.style, pen.lcd, pen.ink, pen.viewWidth)
        val bar = kept.takeIf { key == now } ?: compose(pen, s, u).also { kept = it; key = now }
        c.drawBitmap(bar, 0f, 0f, null)
        pen.stroke.color = pen.ink; pen.stroke.strokeWidth = 2 * u
        c.drawLine(0f, 46 * u, pen.viewWidth.toFloat(), 46 * u, pen.stroke)
        pen.fill.color = pen.ink
    }

    private fun compose(pen: Pen, s: BarState, u: Float): Bitmap {
        val w = pen.viewWidth.coerceAtLeast(1)
        val h = (46 * u).toInt().coerceAtLeast(1)
        val symbols = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val clockLeft = symbols(pen, Canvas(symbols), s, u)

        val pixels = IntArray(w * h)
        symbols.getPixels(pixels, 0, w, 0, 0, w, h)
        val clear = StripeWrap.grow(BooleanArray(w * h) { pixels[it] ushr 24 > FAINTEST }, w, h, GAP * u)
        fun px(v: Float) = Math.round(v * u)
        StripeWrap.block(clear, w, h, px(411f), px(10f), px(463f), px(36f)) // the battery reads as one shape, however empty
        if (s.signalBars < 0) StripeWrap.block(clear, w, h, 0, px(7f), px(NO_SIM_LEFT + pen.measureBold(NO_SIM, NO_SIM_SIZE, TEXT) / u + GAP), px(40f))
        if (clockLeft != null) StripeWrap.block(clear, w, h, px(clockLeft - GAP), px(7f), px(CLOCK_RIGHT + GAP), px(40f)) // and the time as one word

        s.chosen?.let { (l, r) -> StripeWrap.block(clear, w, h, px(l - GAP), 0, px(r + GAP), h) }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // the colour of the window title's stripes
        val stripe = Paint().apply { color = if (pen.style.colour == "colour") Palette.mix(pen.lcd, Palette.of(pen.night).blue, .6f) else pen.ink }
        var y = 9f
        while (y <= 37f) {
            val top = px(y - 1f); val bottom = px(y + 1f).coerceAtLeast(top + 1)
            for (piece in StripeWrap.pieces(clear, w, top, bottom, px(8f), px(472f), px(SHORTEST)))
                canvas.drawRect(piece.first.toFloat(), top.toFloat(), piece.last + 1f, bottom.toFloat(), stripe)
            y += 4f
        }
        canvas.drawBitmap(symbols, 0f, 0f, null)
        return out
    }

    /** The symbols alone, on a clear ground. Returns where the clock's words start (design pixels), or null without a clock. */
    private fun symbols(pen: Pen, c: Canvas, s: BarState, u: Float): Float? {
        val fill = pen.fill; val stroke = pen.stroke; val ink = pen.ink; val lcd = pen.lcd
        val soft = (ink and 0x00FFFFFF) or (SOFT_ALPHA shl 24) // about 3:1 against the paper
        fill.color = ink; stroke.color = ink
        if (s.signalBars < 0) pen.text(c, NO_SIM, NO_SIM_LEFT, 32f, NO_SIM_SIZE, u)
        else for (b in 0 until 4) {
            val height = 8f + 6f * b
            val r = RectF((14 + b * 12) * u, (35 - height) * u, (14 + b * 12 + 8) * u, 35 * u)
            if (b < s.signalBars) c.drawRect(r, fill) else { stroke.strokeWidth = 2 * u; c.drawRect(r, stroke) }
        }
        // one rule for the radios: in use solid, on but unused lighter, switched off lighter with a solid slash
        fun radio(state: Int, cx: Float, shape: () -> Unit) {
            if (state < 0) return
            val colour = if (state == 1) ink else soft
            stroke.color = colour; fill.color = colour; stroke.strokeWidth = 2.8f * u
            shape()
            if (state == 2) {
                stroke.color = lcd; stroke.strokeWidth = 8f * u; c.drawLine((cx - 13) * u, 36 * u, (cx + 13) * u, 10 * u, stroke)
                stroke.color = ink; stroke.strokeWidth = 3f * u; c.drawLine((cx - 13) * u, 36 * u, (cx + 13) * u, 10 * u, stroke)
            }
            stroke.color = ink; fill.color = ink
        }
        radio(s.data, 88f) {
            for ((dx, dir) in listOf(-6f to -1, 6f to 1)) { // up on the left, down on the right
                val x = 88f + dx; val tip = if (dir < 0) 11f else 35f; val tail = if (dir < 0) 35f else 11f
                c.drawLine(x * u, tail * u, x * u, tip * u, stroke)
                c.drawPath(Path().apply { moveTo(x * u, tip * u); lineTo((x - 5) * u, (tip - 7f * dir) * u); lineTo((x + 5) * u, (tip - 7f * dir) * u); close() }, fill)
            }
        }
        radio(s.wifi, 138f) {
            for (r in listOf(9f, 16f, 23f)) c.drawArc(RectF((138 - r) * u, (34 - r) * u, (138 + r) * u, (34 + r) * u), -135f, 90f, false, stroke)
            c.drawCircle(138 * u, 33 * u, 2.8f * u, fill)
        }
        radio(s.bt, 188f) {
            c.drawPath(Path().apply {
                moveTo(180 * u, 16 * u); lineTo(196 * u, 30 * u); lineTo(188 * u, 36 * u); lineTo(188 * u, 10 * u); lineTo(196 * u, 16 * u); lineTo(180 * u, 30 * u)
            }, stroke)
        }
        // what is switched on: torch, alarm, timer, each in its own place
        if (s.torch) {
            stroke.strokeWidth = 2.5f * u
            c.drawRect(224 * u, 17 * u, 240 * u, 23 * u, stroke); c.drawRect(226.5f * u, 23 * u, 237.5f * u, 35 * u, stroke)
            c.drawLine(223 * u, 13 * u, 220 * u, 10 * u, stroke); c.drawLine(232 * u, 12.5f * u, 232 * u, 9 * u, stroke); c.drawLine(241 * u, 13 * u, 244 * u, 10 * u, stroke)
        }
        if (s.alarm) bell(c, 266f, 22.5f, 1.05f, u)
        if (s.timer) stopwatch(c, 298f, 22.5f, 1.2f, u)
        fill.color = ink; stroke.color = ink
        battery(c, s.batteryPct, s.charging, u)
        val time = s.time ?: return null
        // a wide typeface is set smaller rather than allowed to reach the timer
        val wide = pen.measureBold(time, CLOCK_SIZE, TEXT) / u
        val size = if (wide > CLOCK_ROOM) CLOCK_SIZE * CLOCK_ROOM / wide else CLOCK_SIZE
        val drawn = minOf(wide, CLOCK_ROOM)
        pen.text(c, time, CLOCK_RIGHT, 30f, size, u, align = Paint.Align.RIGHT)
        return CLOCK_RIGHT - drawn
    }

    private companion object {
        const val GAP = 3f
        const val SHORTEST = 6f
        const val FAINTEST = 16
        const val SOFT_ALPHA = 0x78
        const val CLOCK_RIGHT = 401f
        const val NO_SIM = "No SIM"
        const val NO_SIM_LEFT = 14f
        const val NO_SIM_SIZE = 24f
        const val CLOCK_SIZE = 27f
        const val CLOCK_ROOM = 86f
    }
}
