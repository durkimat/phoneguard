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
    /** The Appearance settings, and whether the theme is a dark one (a chrome picks its palette by it). */
    val style: Style
    val night: Boolean
    val fill: Paint
    val stroke: Paint
    /** [role] picks which of the three typefaces draws it: [DISPLAY] (the clock), [TEXT] (the default) or [LABEL] (titles and soft keys). */
    fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, u: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT, color: Int = ink, role: Int = TEXT)
    fun marquee(c: Canvas, s0: String, x: Float, y: Float, size: Float, room: Float, u: Float, bold: Boolean = false, color: Int = ink, center: Boolean = false, role: Int = TEXT)
    fun measureBold(s: String, size: Float, role: Int = TEXT): Float
    fun ellipsize(s: String, room: Float, size: Float): String
    fun spectrum(c: Canvas, live: Boolean, u: Float)
}

const val DISPLAY = 0
const val TEXT = 1
const val LABEL = 2

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
    /** The colours of the highlighted row or tile as (background, text), or null for the plain ink on paper. */
    fun selection(pen: Pen): Pair<Int, Int>? = null
    /** The colour of the filled part of an icon (null: the same ink as its outline). */
    fun iconFill(pen: Pen, icon: String, selected: Boolean): Int? = null
    fun nowPlaying(pen: Pen, c: Canvas, m: LauncherModel, u: Float): Boolean = false
    fun notice(pen: Pen, c: Canvas, s: String, u: Float): Boolean = false
    /** A boxed message (a banner, the call-ended panel). True when the chrome drew the box, and the text goes on it in the plain ink colour. */
    fun callout(pen: Pen, c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, u: Float): Boolean = false
    /** How far grid tiles step in from their cell edge. */
    val gridInset: Float get() = 0f
}

/** The plain monochrome look: everything is the painted skin's own default. */
object NokiaChrome : Chrome

/** The colours of the colourful looks (the same five as the mock-up), a set for a light theme and one for a dark one. */
object Palette {
    class Set(val red: Int, val blue: Int, val teal: Int, val amber: Int, val purple: Int)
    private val day = Set(0xFFD22F25.toInt(), 0xFF2B59C3.toInt(), 0xFF1F8A70.toInt(), 0xFFD99A00.toInt(), 0xFF7A4BB8.toInt())
    private val dark = Set(0xFFFF5A4D.toInt(), 0xFF6C95FF.toInt(), 0xFF3FD0A6.toInt(), 0xFFF3B73A.toInt(), 0xFFB48CFF.toInt())
    fun of(night: Boolean) = if (night) dark else day

    /** Teal at 0, amber in the middle, red at 1: how a meter's colour climbs with its level. */
    fun climb(night: Boolean, f: Float): Int = of(night).let { if (f < .5f) mix(it.teal, it.amber, f * 2) else mix(it.amber, it.red, (f - .5f) * 2) }

    fun mix(a: Int, b: Int, f: Float): Int {
        fun ch(s: Int) = ((a shr s and 0xFF) + ((b shr s and 0xFF) - (a shr s and 0xFF)) * f.coerceIn(0f, 1f)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** The colour of an icon's filled part in the colourful look. */
    fun iconColour(night: Boolean, icon: String): Int = of(night).let {
        when (icon) {
            "music", "alarm", "radio", "calendar" -> it.red
            "calls", "settings", "camera" -> it.teal
            "contacts", "calculator", "notes" -> it.amber
            "games", "apps" -> it.purple
            else -> it.blue // messages, about, weather, chat and any app
        }
    }
}
