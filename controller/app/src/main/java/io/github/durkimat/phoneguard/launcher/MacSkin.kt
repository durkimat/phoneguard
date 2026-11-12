package io.github.durkimat.phoneguard.launcher

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** The classic-Mac look: a dithered desktop, a menu bar, and one window around each screen, with the soft keys as push buttons. */
class MacChrome : Chrome {
    private var macTitle = ""
    private var macKeys: Pair<String, String>? = null
    private var deskCache: Pair<Theme, android.graphics.BitmapShader>? = null

    override val gridInset = 5f

    override fun background(pen: Pen, c: Canvas, t: Theme, m: LauncherModel, u: Float) {
        macTitle = if (m.screen == Screen.HOME) "Home" else m.title; macKeys = null
        pen.desktop(c, t, u)
    }
    override fun beginContent(pen: Pen, c: Canvas, u: Float) { c.save(); c.clipRect(11 * u, 94 * u, 469 * u, 569 * u) }
    override fun endContent(pen: Pen, c: Canvas, m: LauncherModel, u: Float) { c.restore(); pen.titleBar(c, m, u); pen.buttons(c, u) }
    override fun title(pen: Pen, c: Canvas, s: String, u: Float): Boolean { macTitle = s; return true }
    override fun softKeys(pen: Pen, c: Canvas, left: String, right: String, u: Float): Boolean { macKeys = left to right; return true }
    override fun fieldTitle(s: String): Boolean { macTitle = s; return true }

    override fun row(m: LauncherModel, idx: Int): RowStyle {
        val icon = if (m.screen == Screen.MENU) m.items.getOrNull(idx) else null
        val tx = if (icon != null) 66f else 20f
        return RowStyle("", icon, tx, 396f - (tx - 20f)) // room for the shortcut digit and the scroll bar
    }
    override fun rowExtras(pen: Pen, c: Canvas, m: LauncherModel, idx: Int, top: Float, selected: Boolean, u: Float) = with(pen) {
        m.items.takeIf { m.screen == Screen.MENU }?.getOrNull(idx)?.let { PixelIcons.draw(c, m.iconFor(it), 20f * u, (top + 13f) * u, 2f * u, if (selected) lcd else ink, if (selected) ink else lcd) }
        val key = if (idx < 9) "${idx + 1}" else if (idx == 9 && m.screen == Screen.MENU) "0" else ""
        if (key.isNotEmpty() && (m.rowMarks.getOrNull(idx) ?: 0) == 0) text(c, key, 440f, top + 40f, 22f, u, align = Paint.Align.RIGHT, color = if (selected) lcd else ink)
    }
    override fun scrollBar(pen: Pen, c: Canvas, first: Int, total: Int, visible: Int, u: Float) = pen.scroll(c, first, total, u)
    override fun nowPlaying(pen: Pen, c: Canvas, m: LauncherModel, u: Float): Boolean { pen.player(c, m, u); return true }

    /** An alert box: shadow, ruled border, plain text. */
    override fun notice(pen: Pen, c: Canvas, s: String, u: Float): Boolean = with(pen) {
        callout(pen, c, 40f, 250f, 440f, 350f, u)
        text(c, s, 240f, 312f, 30f, u, bold = true, align = Paint.Align.CENTER)
        true
    }

    override fun callout(pen: Pen, c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, u: Float): Boolean = with(pen) {
        fill.color = ink; c.drawRect((x0 + 4) * u, (y0 + 4) * u, (x1 + 4) * u, (y1 + 4) * u, fill)
        fill.color = lcd; c.drawRect(x0 * u, y0 * u, x1 * u, y1 * u, fill); fill.color = ink
        stroke.strokeWidth = 3 * u; c.drawRect(x0 * u, y0 * u, x1 * u, y1 * u, stroke)
        stroke.strokeWidth = 1.5f * u; c.drawRect((x0 + 7) * u, (y0 + 7) * u, (x1 - 7) * u, (y1 - 7) * u, stroke)
        true
    }

    /** The classic-Mac desktop: a dithered backdrop, a menu bar for the status symbols, and one window that holds the screen. */
    private fun Pen.desktop(c: Canvas, t: Theme, u: Float) {
        val sh = deskCache?.takeIf { it.first === t }?.second ?: run {
            val bmp = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.setPixel(0, 0, t.desk); bmp.setPixel(1, 1, t.desk); bmp.setPixel(1, 0, t.desk2); bmp.setPixel(0, 1, t.desk2)
            val s = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
            s.setLocalMatrix(android.graphics.Matrix().apply { setScale(2f * u, 2f * u) })
            deskCache = t to s; s
        }
        val d = Paint().apply { shader = sh; isFilterBitmap = false }
        c.drawRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), d)
        fill.color = lcd; c.drawRect(0f, 0f, viewWidth.toFloat(), 46 * u, fill) // the menu bar
        fill.color = ink; c.drawRect(12 * u, 56 * u, 476 * u, 576 * u, fill)  // the window's drop shadow
        fill.color = lcd; c.drawRect(8 * u, 52 * u, 472 * u, 572 * u, fill)
        stroke.strokeWidth = 3 * u; c.drawRect(8 * u, 52 * u, 472 * u, 572 * u, stroke)
        fill.color = ink
    }

    /** Pinstriped title bar with a close box (where Back has somewhere to go) and the title on a plate in the middle. */
    private fun Pen.titleBar(c: Canvas, m: LauncherModel, u: Float) {
        stroke.strokeWidth = 2 * u
        var y = 58f
        while (y < 88f) { c.drawLine(10 * u, y * u, 470 * u, y * u, stroke); y += 4f }
        c.drawLine(8 * u, 92 * u, 472 * u, 92 * u, stroke)
        if (m.screen != Screen.HOME && m.depth > 0) {
            fill.color = lcd; c.drawRect(18 * u, 62 * u, 36 * u, 80 * u, fill); fill.color = ink
            stroke.strokeWidth = 2.5f * u; c.drawRect(18 * u, 62 * u, 36 * u, 80 * u, stroke)
        }
        val room = 340f
        val w = minOf(measureBold(macTitle, 28f), room)
        fill.color = lcd; c.drawRect((240 - w / 2 - 12) * u, 56 * u, (240 + w / 2 + 12) * u, 89 * u, fill); fill.color = ink
        marquee(c, macTitle, 240 - room / 2, 82f, 28f, room, u, bold = true, center = true)
    }

    /** A classic scroll bar down the window's right edge: a ruled track with a solid thumb. */
    private fun Pen.scroll(c: Canvas, first: Int, total: Int, u: Float) {
        val x0 = 454f; val x1 = 468f; val y0 = 104f; val y1 = 104f + LIST_VISIBLE * 62f - 4f
        fill.color = lcd; c.drawRect(x0 * u, y0 * u, x1 * u, y1 * u, fill); fill.color = ink
        stroke.strokeWidth = 2 * u; c.drawRect(x0 * u, y0 * u, x1 * u, y1 * u, stroke)
        val span = y1 - y0 - 6f
        val thumb = maxOf(24f, span * LIST_VISIBLE / total)
        val top = y0 + 3f + (span - thumb) * first / (total - LIST_VISIBLE).coerceAtLeast(1)
        c.drawRect((x0 + 3) * u, top * u, (x1 - 3) * u, (top + thumb) * u, fill)
    }

    /** Now playing as a Mac window: a ruled display for the track, a slider, an inset for the analyser and three push buttons. */
    private fun Pen.player(c: Canvas, m: LauncherModel, u: Float) {
        val mu = m.music
        val radio = m.provider == Provider.RADIO
        stroke.strokeWidth = 2 * u
        c.drawRect(22 * u, 104 * u, 458 * u, 256 * u, stroke)
        c.drawRect(26 * u, 108 * u, 454 * u, 252 * u, stroke)
        val where = if (radio) "Radio" + (if (m.radioIndex >= 0) " " + (m.radioIndex + 1) else "")
            else if (mu.device.isNotEmpty()) (if (mu.thisPhone) "Playing here" else ellipsize("on " + mu.device, 250f, 22f)) else ""
        text(c, where, 38f, 134f, 22f, u)
        if (m.sleepLabel.isNotEmpty()) text(c, "sleep " + m.sleepLabel, 442f, 134f, 22f, u, align = Paint.Align.RIGHT)
        marquee(c, mu.title.ifEmpty { "(no title)" }, 38f, 180f, 34f, 404f, u, bold = true)
        marquee(c, mu.artist, 38f, 214f, 28f, 404f, u)
        val sub = if (mu.from.isNotEmpty()) mu.from else if (mu.album.isNotEmpty() && !mu.album.equals(mu.title, true) && !mu.album.equals(mu.artist, true)) mu.album else ""
        if (sub.isNotEmpty()) marquee(c, sub, 38f, 242f, 22f, 404f, u)
        // the slider: times either side of a ruled track with a square thumb (a radio has no end, so it just says LIVE)
        if (radio) text(c, "LIVE", 240f, 284f, 30f, u, bold = true, align = Paint.Align.CENTER)
        else {
            text(c, io.github.durkimat.phoneguard.music.MusicInfo.clock(mu.positionMs), 22f, 286f, 22f, u)
            if (mu.durationMs > 0) text(c, io.github.durkimat.phoneguard.music.MusicInfo.clock(mu.durationMs), 458f, 286f, 22f, u, align = Paint.Align.RIGHT)
            stroke.strokeWidth = 2 * u; c.drawRect(100 * u, 270 * u, 380 * u, 286 * u, stroke)
            if (mu.durationMs > 0) {
                val tx = 100f + 280f * (mu.positionMs.toFloat() / mu.durationMs).coerceIn(0f, 1f)
                c.drawRect(104 * u, 274 * u, tx * u, 282 * u, fill)
                c.drawRect((tx - 7).coerceAtLeast(96f) * u, 264 * u, (tx + 7).coerceAtMost(384f) * u, 292 * u, fill)
            }
        }
        if (m.spectrum) {
            stroke.strokeWidth = 2 * u; c.drawRect(18 * u, 296 * u, 462 * u, 378 * u, stroke)
            spectrum(c, mu.playing && mu.thisPhone, u)
        }
        // the three buttons: previous, play/pause, next, each with the key that does it
        fun button(x0: Float, x1: Float): RectF {
            val r = RectF(x0 * u, 398 * u, x1 * u, 454 * u)
            fill.color = lcd; c.drawRoundRect(r, 14 * u, 14 * u, fill); fill.color = ink
            stroke.strokeWidth = 2.5f * u; c.drawRoundRect(r, 14 * u, 14 * u, stroke); return r
        }
        button(24f, 144f); button(336f, 456f); button(180f, 300f)
        text(c, if (radio) "< prev" else "4 prev", 84f, 434f, 24f, u, bold = true, align = Paint.Align.CENTER)
        text(c, if (radio) "next >" else "6 next", 396f, 434f, 24f, u, bold = true, align = Paint.Align.CENTER)
        if (mu.playing) { c.drawRect(228 * u, 410 * u, 240 * u, 442 * u, fill); c.drawRect(250 * u, 410 * u, 262 * u, 442 * u, fill) }
        else { val t = android.graphics.Path().apply { moveTo(228f * u, 408f * u); lineTo(260f * u, 426f * u); lineTo(228f * u, 444f * u); close() }; c.drawPath(t, fill) }
        if (radio) {
            text(c, if (m.radioMode == "any") "1-9 presets, hold to set" else "1-9 presets", 240f, 492f, 22f, u, align = Paint.Align.CENTER)
            text(c, "* bars  0 sleep  # source", 240f, 526f, 22f, u, align = Paint.Align.CENTER)
        } else {
            if (m.spSignedIn) text(c, "* shuffle " + (if (mu.shuffle) "on" else "off") + "   1 repeat " + (when (mu.repeat) { "context" -> "all"; "track" -> "one"; else -> "off" }), 240f, 492f, 22f, u, align = Paint.Align.CENTER)
            text(c, "7 bars  0 sleep  # source", 240f, 526f, 22f, u, align = Paint.Align.CENTER)
        }
    }

    /** The two soft keys as Mac push buttons under the window: the left one (the default action) has the heavier ring. */
    private fun Pen.buttons(c: Canvas, u: Float) {
        val (left, right) = macKeys ?: return
        fun button(x0: Float, x1: Float, label: String, heavy: Boolean) {
            val r = RectF(x0 * u, 584 * u, x1 * u, 632 * u)
            fill.color = lcd; c.drawRoundRect(r, 14 * u, 14 * u, fill); fill.color = ink
            stroke.strokeWidth = (if (heavy) 4.5f else 2.5f) * u; c.drawRoundRect(r, 14 * u, 14 * u, stroke)
            text(c, label, (x0 + x1) / 2, 618f, 28f, u, bold = true, align = Paint.Align.CENTER)
        }
        if (left.isNotEmpty()) button(10f, 236f, left, true)
        if (right.isNotEmpty()) button(244f, 470f, right, false)
    }

}
