package io.github.durkimat.phoneguard.launcher

import android.graphics.Canvas
import android.graphics.Paint

/** The drawing helpers a painted skin lends to its chrome, so a chrome never needs to know how text or colours are managed. */
interface Pen {
    val viewWidth: Int
    val viewHeight: Int
    val lcd: Int
    val ink: Int
    val accent: Int
    val fill: Paint
    val stroke: Paint
    fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, u: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT, color: Int = ink)
    fun marquee(c: Canvas, s0: String, x: Float, y: Float, size: Float, room: Float, u: Float, bold: Boolean = false, color: Int = ink, center: Boolean = false)
    fun measureBold(s: String, size: Float): Float
    fun ellipsize(s: String, room: Float, size: Float): String
    fun spectrum(c: Canvas, live: Boolean, u: Float)
}

/** How a chrome wants one list row set out: the text before the label, an icon for a main-menu row, and where the label sits. */
data class RowStyle(val prefix: String, val icon: MenuItem?, val tx: Float, val room: Float)

/**
 * What frames a screen. The painted skin draws each screen's content; the chrome draws everything around it and may take over
 * a few whole parts (the title, the soft keys, list rows, the player, notices). Every hook defaults to "leave it to the plain look",
 * so a new chrome only says what is different.
 */
interface Chrome {
    /** Behind everything, before the status symbols. */
    fun background(pen: Pen, c: Canvas, t: Theme, m: LauncherModel, u: Float) {}
    /** Called before and after the screen's own content (a chrome that frames a window clips to it in between). */
    fun beginContent(pen: Pen, c: Canvas, u: Float) {}
    fun endContent(pen: Pen, c: Canvas, m: LauncherModel, u: Float) {}
    /** True when the chrome took the title or the soft keys itself. */
    fun title(pen: Pen, c: Canvas, s: String, u: Float): Boolean = false
    fun softKeys(pen: Pen, c: Canvas, left: String, right: String, u: Float): Boolean = false
    /** The write screen's To / Search / Password line, for a chrome that shows it in its own title bar. */
    fun fieldTitle(s: String): Boolean = false
    fun row(m: LauncherModel, idx: Int): RowStyle? = null
    fun rowExtras(pen: Pen, c: Canvas, m: LauncherModel, idx: Int, top: Float, selected: Boolean, u: Float) {}
    fun scrollBar(pen: Pen, c: Canvas, first: Int, total: Int, visible: Int, u: Float) {}
    fun nowPlaying(pen: Pen, c: Canvas, m: LauncherModel, u: Float): Boolean = false
    fun notice(pen: Pen, c: Canvas, s: String, u: Float): Boolean = false
    /** A boxed message (a banner, the call-ended panel). True when the chrome drew the box, and the text goes on it in the plain ink colour. */
    fun callout(pen: Pen, c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, u: Float): Boolean = false
    /** How far grid tiles step in from their cell edge. */
    val gridInset: Float get() = 0f
}

/** The plain monochrome look: everything is the painted skin's own default. */
object NokiaChrome : Chrome
