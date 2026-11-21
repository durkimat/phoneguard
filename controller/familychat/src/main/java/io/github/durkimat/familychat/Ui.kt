package io.github.durkimat.familychat

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The look: light, plain, large type, the system sans. Everything is built in code; there are no layout files. */
object Ui {
    const val GROUND = 0xFFF4F7FB.toInt(); const val CARD = 0xFFFFFFFF.toInt(); const val BORDER = 0xFFDCE3EA.toInt()
    const val INK = 0xFF14202B.toInt(); const val MUTED = 0xFF566370.toInt(); const val ACCENT = 0xFF0A7D4F.toInt()
    const val ACCENT_SOFT = 0xFFD6F2E1.toInt(); const val DEEP = 0xFF075B38.toInt(); const val MINE = 0xFFD9F5D0.toInt(); const val SAND = 0xFFEFE9DF.toInt(); const val WARN = 0xFFFFF1D6.toInt(); const val WARN_TEXT = 0xFF5A3B00.toInt()
    const val DANGER = 0xFFA12B2B.toInt(); const val THEIRS = 0xFFE6EBF1.toInt(); const val DISABLED = 0xFFB8C4CE.toInt()

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun shape(c: Context, fill: Int, radius: Int = 16, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(c, radius).toFloat(); if (stroke != 0) setStroke(dp(c, 1), stroke)
    }

    fun text(c: Context, s: CharSequence, sp: Float = 17f, color: Int = INK, bold: Boolean = false): TextView = TextView(c).apply {
        text = s; textSize = sp; setTextColor(color); typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.SANS_SERIF
        includeFontPadding = false; setLineSpacing(0f, 1.15f)
    }

    fun title(c: Context, s: String) = text(c, s, 28f, INK, true)

    fun params(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, c: Context? = null, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).also { if (c != null) it.topMargin = dp(c, top) }

    fun col(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    fun card(c: Context): LinearLayout = col(c).apply { background = shape(c, CARD, 16, BORDER); setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12)) }

    /** 56 dp high, 16 dp corners. [primary] teal with white text; otherwise a plain outlined button. */
    fun button(c: Context, label: String, primary: Boolean = true, danger: Boolean = false, onClick: () -> Unit): TextView = TextView(c).apply {
        text = label; textSize = 18f; gravity = Gravity.CENTER; minHeight = dp(c, 56); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else if (danger) DANGER else ACCENT)
        background = if (primary) shape(c, ACCENT) else shape(c, CARD, 16, if (danger) DANGER else BORDER)
        setPadding(dp(c, 16), 0, dp(c, 16), 0); isClickable = true; isFocusable = true
        setOnClickListener { if (isEnabled) onClick() }
    }

    fun enable(b: TextView, on: Boolean, primary: Boolean = true) {
        b.isEnabled = on
        if (primary) { b.background = shape(b.context, if (on) ACCENT else DISABLED) }
    }

    fun input(c: Context, hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(c).apply {
        this.hint = hint; textSize = 18f; setTextColor(INK); setHintTextColor(MUTED); inputType = type; minHeight = dp(c, 56)
        background = shape(c, CARD, 16, BORDER); setPadding(dp(c, 16), 0, dp(c, 16), 0); imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI; setSingleLine(true)
    }

    /** A screen: [body] scrolls; [footer] (buttons) stays fixed at the bottom. */
    fun screen(a: Activity, body: View, footer: View? = null, header: View? = null): View {
        val root = col(a).apply { setBackgroundColor(GROUND) }
        if (header != null) root.addView(header, params())
        root.addView(ScrollView(a).apply { isFillViewport = true; addView(body) }, params(h = 0, weight = 1f))
        if (footer != null) root.addView(footer, params())
        fitInsets(root)
        return root
    }

    /**
     * Keeps a screen clear of the status bar, the navigation bar and the keyboard. When the app is drawn edge to edge (forced from target 35,
     * and on newer Android) the insets arrive here and become padding; on a window the system already fits they are zero and nothing changes.
     */
    fun fitInsets(root: View) {
        root.setOnApplyWindowInsetsListener { v, ins ->
            val i = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom); ins
        }
    }

    fun pad(c: Context): LinearLayout = col(c).apply { setPadding(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 20)) }

    fun footer(c: Context, vararg buttons: View): LinearLayout = col(c).apply {
        setBackgroundColor(GROUND); setPadding(dp(c, 20), dp(c, 8), dp(c, 20), dp(c, 16))
        buttons.forEachIndexed { i, b -> addView(b, params(c = c, top = if (i == 0) 0 else 8)) }
    }

    fun warning(c: Context, head: String, body: String): LinearLayout = col(c).apply {
        background = shape(c, WARN, 16); setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
        addView(text(c, head, 18f, WARN_TEXT, true)); addView(text(c, body, 17f, WARN_TEXT), params(c = c, top = 6))
    }

    fun circle(c: Context, letter: String, size: Int = 48): TextView = text(c, letter, 20f, ACCENT, true).apply {
        gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT_SOFT) }; layoutParams = LinearLayout.LayoutParams(dp(c, size), dp(c, size))
    }

    fun row(c: Context, label: String, value: String = "", chevron: Boolean = false, danger: Boolean = false, onClick: (() -> Unit)? = null): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(c, 56)
        addView(text(c, label, 17f, if (danger) DANGER else INK, danger), params(0, weight = 1f))
        if (value.isNotEmpty()) addView(text(c, value, 17f, MUTED).apply { gravity = Gravity.END }, params(w = ViewGroup.LayoutParams.WRAP_CONTENT))
        if (chevron) addView(text(c, "›", 24f, MUTED).apply { setPadding(dp(c, 10), 0, 0, 0) }, params(w = ViewGroup.LayoutParams.WRAP_CONTENT))
        if (onClick != null) { isClickable = true; isFocusable = true; setOnClickListener { onClick() } }
    }

    fun divider(c: Context) = View(c).apply { setBackgroundColor(BORDER); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1)) }

    fun timeText(ts: Long, now: Long = System.currentTimeMillis()): String {
        val cal = java.util.Calendar.getInstance(); val today = java.util.Calendar.getInstance().apply { timeInMillis = now }
        cal.timeInMillis = ts
        fun day(c: java.util.Calendar) = c.get(java.util.Calendar.YEAR) * 1000 + c.get(java.util.Calendar.DAY_OF_YEAR)
        val yest = java.util.Calendar.getInstance().apply { timeInMillis = now; add(java.util.Calendar.DAY_OF_YEAR, -1) }
        return when (day(cal)) {
            day(today) -> "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
            day(yest) -> "Yesterday"
            else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(ts))
        }
    }
}

/** A drawn icon (no emoji, no files): lock, back, send, plus, close, smile, picture, play, photo, video. */
class IconView(c: Context, private val kind: String, private val color: Int = Ui.MUTED) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(c, 2).toFloat(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun onDraw(cv: Canvas) {
        p.color = color
        val w = width.toFloat(); val h = height.toFloat(); val u = minOf(w, h) / 24f
        cv.save(); cv.translate((w - 24f * u) / 2, (h - 24f * u) / 2); cv.scale(u, u); p.strokeWidth = 2f
        when (kind) {
            "lock" -> { cv.drawRoundRect(4f, 11f, 20f, 21f, 2f, 2f, p); cv.drawPath(Path().apply { moveTo(8f, 11f); lineTo(8f, 7f); arcTo(8f, 3f, 16f, 11f, 180f, 180f, false); lineTo(16f, 11f) }, p) }
            "back" -> cv.drawPath(Path().apply { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }, p)
            "plus" -> { cv.drawLine(12f, 5f, 12f, 19f, p); cv.drawLine(5f, 12f, 19f, 12f, p) }
            "close" -> { cv.drawLine(6f, 6f, 18f, 18f, p); cv.drawLine(18f, 6f, 6f, 18f, p) }
            "smile" -> { cv.drawCircle(12f, 12f, 9f, p); cv.drawPoint(9f, 10f, p); cv.drawPoint(15f, 10f, p); cv.drawPath(Path().apply { moveTo(8f, 14f); quadTo(12f, 18.5f, 16f, 14f) }, p) }
            "picture" -> { cv.drawRoundRect(3f, 5f, 21f, 19f, 2f, 2f, p); cv.drawPath(Path().apply { moveTo(4f, 17f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(16f, 12f); lineTo(20f, 17f) }, p); cv.drawCircle(16f, 9f, 1.5f, p) }
            "play" -> { p.style = Paint.Style.FILL; cv.drawPath(Path().apply { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() }, p); p.style = Paint.Style.STROKE }
            "photo" -> { cv.drawRoundRect(3f, 6f, 21f, 19f, 2f, 2f, p); cv.drawCircle(12f, 12.5f, 3.5f, p) }
            "video" -> { cv.drawRoundRect(3f, 6f, 15f, 18f, 2f, 2f, p); cv.drawPath(Path().apply { moveTo(15f, 11f); lineTo(21f, 8f); lineTo(21f, 16f); lineTo(15f, 13f) }, p) }
            else -> cv.drawPath(Path().apply { moveTo(4f, 12f); lineTo(20f, 4f); lineTo(14f, 20f); lineTo(11f, 13f); close() }, p)
        }
        cv.restore()
    }
}

/** Lays children out left to right and wraps them (the chosen-group chips). */
class FlowLayout(c: Context) : ViewGroup(c) {
    private val gap = Ui.dp(c, 8)
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec); var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i); v.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + v.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += v.measuredWidth + gap; rowH = maxOf(rowH, v.measuredHeight)
        }
        setMeasuredDimension(maxW, y + rowH)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l; var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (x > 0 && x + v.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
            x += v.measuredWidth + gap; rowH = maxOf(rowH, v.measuredHeight)
        }
    }
}

/** A column only as wide as its content, but never wider than [Rich.BUBBLE_MAX] of the width it is offered (a chat bubble with its name, reactions and time). */
class CappedColumn(c: Context) : LinearLayout(c) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val offered = MeasureSpec.getSize(wSpec)
        super.onMeasure(if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED) wSpec else MeasureSpec.makeMeasureSpec(Rich.bubbleCap(offered), MeasureSpec.AT_MOST), hSpec)
    }
}
