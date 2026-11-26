package io.github.durkimat.phoneguard.launcher

import io.github.durkimat.media.Graphemes
import io.github.durkimat.media.Rich
import io.github.durkimat.phoneguard.gallery.ChatPictures
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.github.durkimat.phoneguard.music.PlaylistOffline
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The painted skins: the content of each screen is drawn here, and a [Chrome] decides what frames it (a plain Nokia screen, a Mac window). It only draws. Everything is laid out on a 480 by 640
 * design grid and scaled to the real screen, so another skin can use a different grid or none.
 */
open class PaintedSkin(private val chrome: Chrome, override val id: String) : LauncherSkin {

    private var model: LauncherModel? = null
    /** A touch that lifted at this height on the 640 high design grid (only reaches us where touch is allowed). */
    var onTap: ((Float, Float) -> Unit)? = null
    private var info: HomeInfo? = null
    private var view: Canvas2? = null

    override fun create(context: Context): View = Canvas2(context).also { view = it }

    override fun render(model: LauncherModel, info: HomeInfo) {
        this.model = model; this.info = info
        model.wrapper = { view?.wrap(it) ?: listOf(it) }
        model.pageLines = 11
        view?.invalidate()
    }

    private inner class Canvas2(context: Context) : View(context), Pen {
        private val typefaces = HashMap<String, Typeface>()
        /** The typeface and choice for each role (clock, text, labels), set at the start of every draw. */
        private val faces = arrayOfNulls<Typeface>(3)
        private val choices = Array(3) { Fonts.byId(null) }
        private fun scaleOf(role: Int) = choices[role].let { when (role) { DISPLAY -> it.display; LABEL -> it.label; else -> it.text } }
        override var style = Style()
        override var night = false
        private var downY = 0f
        private var downX = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var dragged = false

        private fun load(id: String): Typeface = try {
            val f = Fonts.byId(id)
            when {
                f.asset != null -> Typeface.createFromAsset(context.assets, "fonts/" + f.asset)
                f.id == "sans" -> Typeface.SANS_SERIF
                else -> Typeface.MONOSPACE
            }
        } catch (_: Exception) { Typeface.MONOSPACE }

        override var lcd = 0
        override var ink = 0
        override var accent = 0
        override val viewWidth get() = width
        override val viewHeight get() = height
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
        override val fill = Paint()
        private val tilePaint = Paint().apply { isFilterBitmap = true }
        override val stroke = Paint().apply { style = Paint.Style.STROKE }

        init {
            isFocusable = false
            // a Nokia has no touchscreen; where touch is allowed, a tap on the weather opens the forecast
            setOnTouchListener { v, ev ->
                val k = v.width / 480f
                when (ev.action) {
                    android.view.MotionEvent.ACTION_DOWN -> { downY = ev.y / k; lastY = downY; downX = ev.x / k; lastX = downX; dragged = false }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val y = ev.y / k
                        val x = ev.x / k
                        val inBadgeRow = model?.screen == Screen.HOME && downY in ACT_TAP_TOP..ACT_TAP_BOTTOM && (model?.badgeOverflow() ?: 0f) > 0f
                        if (inBadgeRow) {
                            // the symbol row is wider than the screen: a finger slides it left and right
                            if (Math.abs(x - downX) > 14f) dragged = true
                            if (dragged) { if (model?.dragBadges(lastX - x) == true) invalidate(); lastX = x }
                            return@setOnTouchListener true
                        }
                        // a swipe up shows later rows: every 62 design pixels of travel is one row
                        if (Math.abs(y - downY) > 14f) dragged = true
                        if (dragged) {
                            val rows = ((lastY - y) / 62f).toInt()
                            if (rows != 0) { if (model?.scrollBy(rows) == true) invalidate(); lastY -= rows * 62f }
                        }
                    }
                    android.view.MotionEvent.ACTION_UP -> if (!dragged) onTap?.invoke(ev.x / k, ev.y / k)
                }
                true
            }
        }

        private var lastTrace = ""
        /**
         * Debug builds say what is on screen (screen, row, how many screens are behind it, layout, number of rows, look) to logcat
         * under the tag PGState, once per change and never any row text, so `rig/journey.py` can walk the phone and check it.
         */
        private fun trace(m: LauncherModel) {
            if (!io.github.durkimat.phoneguard.BuildConfig.DEBUG) return
            val s = "screen=${m.screen} sel=${m.selected} depth=${m.depth} layout=${m.layout} rows=${m.rows.size} theme=${m.theme} skin=${this@PaintedSkin.id} menu=${m.menuStyle} notice=${m.notice != null}"
            if (s != lastTrace) { lastTrace = s; android.util.Log.d("PGState", s) }
        }

        /** The longest overflow among the scrolling lines of the frame being drawn, and of the last frame: every scrolling line in a frame moves on the one shared clock (see [ScrollClock]). */
        private var frameTravel = 0f
        private var syncTravel = 0f

        override fun onDraw(c: Canvas) {
            val m = model ?: return
            val i = info ?: return
            val u = width / 480f
            frameTravel = 0f
            trace(m)
            val t = Themes.byId(m.theme)
            lcd = t.lcd; ink = t.ink; accent = m.accent ?: t.accent; fill.color = ink; stroke.color = ink
            val f = if (t.font != null && m.font == Fonts.DEFAULT) t.font else Fonts.byId(m.font).id
            style = m.style; night = android.graphics.Color.luminance(t.lcd) < 0.5f
            for (r in 0..2) {
                val id = (when (r) { DISPLAY -> style.fontDisplay; LABEL -> style.fontLabel; else -> style.fontText } ?: f).let { Fonts.byId(it).id }
                choices[r] = Fonts.byId(id); faces[r] = typefaces.getOrPut(id) { load(id) }
            }
            c.drawColor(lcd)
            chrome.background(this, c, t, m, u)
            statusLine(c, i, u, m.screen != Screen.HOME)
            chrome.beginContent(this, c, u)
            when (m.screen) {
                Screen.HOME -> { home(c, i, u); i.dataView?.let { dataWidget(c, it, i.time, u) }; homeLower(c, m, i, u) }
                Screen.WEATHER -> weatherScreen(c, m, u)
                Screen.BATTERY -> batteryScreen(c, m, u)
                Screen.TIMER -> timerScreen(c, m, u)
                Screen.STOPWATCH -> stopwatchScreen(c, m, u)
                Screen.RING -> ringScreen(c, m, u)
                Screen.NOTE -> noteScreen(c, m, u)
                Screen.MUSIC -> musicScreen(c, m, u)
                Screen.THREAD -> thread(c, m, u)
                Screen.EVENT, Screen.MAILMSG -> page(c, m, u)
                Screen.CALMONTH -> calMonth(c, m, u)
                Screen.WRITE -> write(c, m, u)
                Screen.WHEREMAP -> whereMap(c, m, u)
                Screen.CALC -> calc(c, m, u)
                Screen.GAMEHELP -> gameHelp(c, m, u)
                Screen.SNAKE -> snake(c, m, u)
                Screen.PAIRS -> pairs(c, m, u)
                Screen.SHOOTER -> shooter(c, m, u)
                Screen.SEEDS -> seeds(c, m, u)
                Screen.WAPAIR -> waPair(c, m, u)
                Screen.DIAL -> dial(c, m, u)
                Screen.CALL -> callScreen(c, m, u)
                else -> menu(c, m, u)
            }
            m.homeFocusTarget()?.let { t -> // the symbol chosen with the keypad on the home screen
                stroke.strokeWidth = 3f * u; stroke.color = ink
                c.drawRoundRect(RectF(t.l * u, t.t * u, t.r * u, t.b * u), 8f * u, 8f * u, stroke)
            }
            m.notice?.let { notice(c, it, u) }
            chrome.endContent(this, c, m, u)
            syncTravel = frameTravel
        }

        override fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, u: Float, bold: Boolean, align: Paint.Align, color: Int, role: Int) {
            val fc = choices[role]
            p.typeface = faces[role] ?: Typeface.MONOSPACE
            p.textSize = size * scaleOf(role) * u; p.isFakeBoldText = bold; p.textAlign = align; p.color = color
            p.letterSpacing = if (role == DISPLAY) fc.tracking else 0f
            c.drawText(if (role == LABEL && fc.caps) s.uppercase() else s, x * u, y * u, p)
            p.letterSpacing = 0f
        }

        private fun statusLine(c: Canvas, i: HomeInfo, u: Float, clock: Boolean) {
            if (i.signalBars < 0) text(c, "No SIM", 14f, 32f, 24f, u)
            else for (b in 0 until 4) {
                val h = 8f + 7f * b
                val r = RectF((14 + b * 12) * u, (36 - h) * u, (14 + b * 12 + 8) * u, 36 * u)
                if (b < i.signalBars) c.drawRect(r, fill) else { stroke.strokeWidth = 2 * u; c.drawRect(r, stroke) }
            }
            // battery: outline, level, nub
            val bx = 480f - 14 - 56 + 1f // the nub's last ink column is 466, like the data bar's and the right soft key's
            stroke.strokeWidth = 3 * u
            c.drawRect(bx * u, 10 * u, (bx + 52) * u, 36 * u, stroke)
            c.drawRect((bx + 52) * u, 17 * u, (bx + 56) * u, 29 * u, fill)
            c.drawRect((bx + 5) * u, 15 * u, (bx + 5 + 42f * i.batteryPct / 100f) * u, 31 * u, fill)
            // the level reads inside the icon: ink over the empty part, the background colour over the filled part
            val label = if (i.charging) "+${i.batteryPct}" else "${i.batteryPct}%"
            val lx = bx + 26f
            val level = (bx + 5 + 42f * i.batteryPct / 100f) * u
            c.save(); c.clipRect(level, 15 * u, (bx + 47) * u, 31 * u)
            text(c, label, lx, 29f, 17f, u, bold = true, align = Paint.Align.CENTER); c.restore()
            c.save(); c.clipRect((bx + 5) * u, 15 * u, level, 31 * u)
            text(c, label, lx, 29f, 17f, u, bold = true, align = Paint.Align.CENTER, color = lcd); c.restore()
            // the time, where the percentage used to be (the home screen has its big clock)
            if (clock) text(c, i.time, bx - 8, 30f, 22f, u, align = Paint.Align.RIGHT) // the same middle line as the icons (the battery's centre is y 23)
            // Wi-Fi: a dot and three arcs, solid when connected, faint when on with no connection, faint with a slash when switched off (not drawn where the mode hides it)
            if (i.wifi >= 0) {
                val faint = i.wifi != 1
                val col = if (faint) (ink and 0x00FFFFFF) or (0x55 shl 24) else ink
                stroke.color = col; fill.color = col; stroke.strokeWidth = 2.5f * u
                val cx = 138f; val by = 33f
                for (r in listOf(9f, 16f, 23f)) c.drawArc(RectF((cx - r) * u, (by - r) * u, (cx + r) * u, (by + r) * u), -135f, 90f, false, stroke)
                c.drawCircle(cx * u, (by - 1f) * u, 2.6f * u, fill)
                stroke.color = ink; fill.color = ink
                if (i.wifi == 2) slash(c, cx, u)
            }
            // mobile data (a phone with a SIM): an up and a down arrow, solid when it is the connection in use, faint when idle, slashed when off
            if (i.data >= 0) {
                val col = if (i.data == 1) ink else (ink and 0x00FFFFFF) or (0x55 shl 24)
                stroke.color = col; fill.color = col; stroke.strokeWidth = 2.5f * u
                val cx = 88f
                for ((dx, dir) in listOf(-6f to -1, 6f to 1)) { // up on the left, down on the right
                    val x = cx + dx; val tip = if (dir < 0) 12f else 34f; val tail = if (dir < 0) 34f else 12f
                    c.drawLine(x * u, tail * u, x * u, tip * u, stroke)
                    val head = android.graphics.Path().apply { moveTo(x * u, tip * u); lineTo((x - 5) * u, (tip - 6f * dir) * u); lineTo((x + 5) * u, (tip - 6f * dir) * u); close() }
                    c.drawPath(head, fill)
                }
                stroke.color = ink; fill.color = ink
                if (i.data == 2) slash(c, cx, u)
            }
            // Bluetooth: the rune, solid when a device is connected, faint when on with nothing connected, faint with a slash when off
            if (i.bt >= 0) {
                val col = if (i.bt == 1) ink else (ink and 0x00FFFFFF) or (0x55 shl 24)
                stroke.color = col; stroke.strokeWidth = 2.5f * u
                val cx = 188f; val cy = 22f
                val rune = android.graphics.Path().apply {
                    moveTo((cx - 8) * u, (cy - 7) * u); lineTo((cx + 8) * u, (cy + 7) * u); lineTo(cx * u, (cy + 13) * u)
                    lineTo(cx * u, (cy - 13) * u); lineTo((cx + 8) * u, (cy - 7) * u); lineTo((cx - 8) * u, (cy + 7) * u)
                }
                c.drawPath(rune, stroke)
                stroke.color = ink
                if (i.bt == 2) slash(c, cx, u)
            }
            // a small torch while the LED is on (drawn only because the screen is on and showing our screens)
            if (io.github.durkimat.phoneguard.keys.Torch.on) {
                stroke.strokeWidth = 2.5f * u
                c.drawRect(228 * u, 20 * u, 252 * u, 28 * u, stroke)                        // the head
                c.drawRect(231 * u, 28 * u, 249 * u, 42 * u, stroke)                         // the body
                c.drawLine(225 * u, 14 * u, 220 * u, 9 * u, stroke); c.drawLine(240 * u, 12 * u, 240 * u, 5 * u, stroke); c.drawLine(255 * u, 14 * u, 260 * u, 9 * u, stroke) // the light
            }
            // a small bell while an alarm is switched on or the timer is running
            if (i.alarmSet) bellIcon(c, 298f, 22.5f, 1f, u)
            if (timerLeft(i) > 0) timerIcon(c, if (i.alarmSet) 326f else 298f, 22.5f, 1f, u) // beside the bell while a timer runs
            stroke.strokeWidth = 2 * u
            c.drawLine(0f, 46 * u, width.toFloat(), 46 * u, stroke)
        }

        /** Seconds left on the running timer (0 when none, or it is up). */
        private fun timerLeft(i: HomeInfo): Int = if (i.timerEndAt <= 0) 0 else ((i.timerEndAt - io.github.durkimat.phoneguard.Clock.now() + 999) / 1000).toInt().coerceAtLeast(0)

        /** "mm:ss" under an hour, "h:mm" over. */
        private fun timerText(s: Int) = if (s >= 3600) "%d:%02d".format(s / 3600, s / 60 % 60) else "%02d:%02d".format(s / 60, s % 60)

        /** A small stopwatch face centred on ([cx], [cy]): a ring, a hand and a button on top; [k] scales it like the bell. */
        private fun timerIcon(c: Canvas, cx: Float, cy: Float, k: Float, u: Float) {
            stroke.strokeWidth = 2.4f * k * u
            c.drawCircle(cx * u, (cy + 2f * k) * u, 8.5f * k * u, stroke)
            c.drawLine(cx * u, (cy + 2f * k) * u, cx * u, (cy - 3.5f * k) * u, stroke)
            c.drawLine(cx * u, (cy + 2f * k) * u, (cx + 4f * k) * u, (cy + 2f * k) * u, stroke)
            c.drawRect((cx - 2.5f * k) * u, (cy - 9.5f * k) * u, (cx + 2.5f * k) * u, (cy - 7.5f * k) * u, fill)
        }

        /** A small bell centred on ([cx], [cy]); [k] scales it (1 = 25 px tall). */
        private fun bellIcon(c: Canvas, cx: Float, cy: Float, k: Float, u: Float) {
            fun px(x: Float) = (cx + x * k) * u
            fun py(y: Float) = (cy + (y - 22.5f) * k) * u
            val bell = android.graphics.Path().apply {
                moveTo(px(-10f), py(30f)); lineTo(px(-7f), py(27f)); lineTo(px(-7f), py(19f)); quadTo(px(-7f), py(12f), px(0f), py(12f))
                quadTo(px(7f), py(12f), px(7f), py(19f)); lineTo(px(7f), py(27f)); lineTo(px(10f), py(30f)); close()
            }
            c.drawPath(bell, fill); c.drawCircle(px(0f), py(11f), 2f * k * u, fill); c.drawCircle(px(0f), py(34f), 2.6f * k * u, fill)
        }

        /** The diagonal that marks a switched-off radio, in the same faint tone (and transparency) as the symbol it crosses. */
        private fun slash(c: Canvas, cx: Float, u: Float) {
            stroke.color = (ink and 0x00FFFFFF) or (0x55 shl 24); stroke.strokeWidth = 3f * u
            c.drawLine((cx - 15) * u, 37f * u, (cx + 15) * u, 8f * u, stroke)
            stroke.color = ink
        }

        // The two labels are the D-pad's Left and Right: a small arrow shows which way to press.
        private fun softKeys(c: Canvas, left: String, right: String, u: Float) {
            if (chrome.softKeys(this, c, left, right, u)) return
            stroke.strokeWidth = 2 * u
            c.drawLine(0f, (640 - 64) * u, width.toFloat(), (640 - 64) * u, stroke)
            val cy = 606f
            if (left.isNotEmpty()) {
                arrow(c, 23f, cy, -1, u) // the triangle's ink starts at column 14, like the signal bars and the data text
                text(c, left, 46f, 614f, 30f, u, bold = true, role = LABEL)
            }
            if (right.isNotEmpty()) {
                arrow(c, 458f, cy, 1, u)
                text(c, right, 434f, 614f, 30f, u, bold = true, align = Paint.Align.RIGHT, role = LABEL)
            }
        }

        private fun arrow(c: Canvas, x: Float, y: Float, dir: Int, u: Float) {
            val path = android.graphics.Path().apply {
                moveTo((x + 10 * dir) * u, y * u); lineTo((x - 10 * dir) * u, (y - 12) * u); lineTo((x - 10 * dir) * u, (y + 12) * u); close()
            }
            c.drawPath(path, fill)
        }

        /** New texts, new WhatsApp messages and missed calls as small outline symbols with a count, centred in a row. */
        private fun badges(c: Canvas, i: HomeInfo, u: Float) {
            val m = model ?: return
            val over = m.badgeOverflow()
            if (over > 0f) {
                // too many symbols for the width: they drift gently left and back, pausing at each end, unless a finger has just moved them
                val now = System.currentTimeMillis()
                val chosen = m.homeBadgeIndex()
                if (chosen >= 0) m.badgeOffset = (85f + 130f * chosen - 240f).coerceIn(0f, over) // a symbol chosen with the keys is brought into view and held
                else if (now - m.badgeDragAt > 5000L) {
                    val speed = 30f; val rest = 1500L; val travelMs = (over / speed * 1000).toLong()
                    val t = now % (2 * (travelMs + rest))
                    m.badgeOffset = when { t < rest -> 0f; t < rest + travelMs -> (t - rest) / 1000f * speed; t < 2 * rest + travelMs -> over; else -> over - (t - 2 * rest - travelMs) / 1000f * speed }.coerceIn(0f, over)
                }
                postInvalidateDelayed(60)
                c.save(); c.clipRect(0f, 455f * u, width.toFloat(), 570f * u)
            }
            val sh = if (over == 0f) BandGeometry.BADGE_NUDGE + BandGeometry.axisShift(BandGeometry.divider(clockColonX(i.time, u))) else 0f // a row that fits is centred on the axis
            i.badges.forEachIndexed { n, b ->
                val cx = m.badgeX(n, i.badges.size) + sh
                if (b.kind == BadgeKind.MUSIC) { badgeIcon(c, b.kind, cx, 512f, u); return@forEachIndexed } // a symbol only, no count
                val label = if (b.count > 99) "99+" else b.count.toString()
                p.typeface = faces[TEXT] ?: Typeface.MONOSPACE; p.textSize = 36f * scaleOf(TEXT) * u; p.isFakeBoldText = true
                val tw = p.measureText(label) / u
                val left = cx - (44f + 10f + tw) / 2
                badgeIcon(c, b.kind, left + 22f, 512f, u)
                text(c, label, left + 54f, 526f, 36f, u, bold = true)
            }
            if (over > 0f) c.restore()
        }

        private fun badgeIcon(c: Canvas, kind: BadgeKind, cx: Float, cy: Float, u: Float) {
            stroke.strokeWidth = 3 * u
            when (kind) {
                BadgeKind.SMS -> { // a square speech bubble with two lines of text: a text message
                    val r = RectF((cx - 20) * u, (cy - 16) * u, (cx + 20) * u, (cy + 10) * u)
                    c.drawRoundRect(r, 3 * u, 3 * u, stroke)
                    val tail = android.graphics.Path().apply { moveTo((cx - 10) * u, (cy + 10) * u); lineTo((cx - 14) * u, (cy + 20) * u); lineTo((cx - 2) * u, (cy + 10) * u) }
                    c.drawPath(tail, stroke)
                    c.drawLine((cx - 11) * u, (cy - 6) * u, (cx + 11) * u, (cy - 6) * u, stroke); c.drawLine((cx - 11) * u, (cy + 1) * u, (cx + 4) * u, (cy + 1) * u, stroke)
                }
                BadgeKind.WHATSAPP -> { // a round bubble with a tail and a small handset inside: a WhatsApp message
                    c.drawCircle(cx * u, (cy - 2) * u, 18 * u, stroke)
                    val tail = android.graphics.Path().apply { moveTo((cx - 16) * u, (cy + 10) * u); lineTo((cx - 21) * u, (cy + 21) * u); lineTo((cx - 6) * u, (cy + 15) * u) }
                    c.drawPath(tail, stroke)
                    stroke.strokeWidth = 4 * u
                    c.drawArc(RectF((cx - 9) * u, (cy - 9) * u, (cx + 9) * u, (cy + 9) * u), 200f, 140f, false, stroke)
                    stroke.strokeWidth = 3 * u
                }
                BadgeKind.MUSIC -> { // a pair of beamed quavers: music is playing
                    fill.color = ink
                    c.save(); c.rotate(-20f, (cx - 11) * u, (cy + 12) * u); c.drawOval(RectF((cx - 21) * u, (cy + 6) * u, (cx - 3) * u, (cy + 18) * u), fill); c.restore()
                    c.save(); c.rotate(-20f, (cx + 11) * u, (cy + 8) * u); c.drawOval(RectF((cx + 3) * u, (cy + 2) * u, (cx + 21) * u, (cy + 14) * u), fill); c.restore()
                    stroke.strokeWidth = 3.5f * u
                    c.drawLine((cx - 4) * u, (cy + 11) * u, (cx - 4) * u, (cy - 16) * u, stroke)
                    c.drawLine((cx + 20) * u, (cy + 7) * u, (cx + 20) * u, (cy - 20) * u, stroke)
                    stroke.strokeWidth = 7 * u
                    c.drawLine((cx - 4) * u, (cy - 15) * u, (cx + 20) * u, (cy - 19) * u, stroke)
                    stroke.strokeWidth = 3 * u
                }
                BadgeKind.MAIL -> { // an envelope: a box with the flap folded down to a point
                    c.drawRect((cx - 20) * u, (cy - 12) * u, (cx + 20) * u, (cy + 14) * u, stroke)
                    c.drawLine((cx - 20) * u, (cy - 12) * u, cx * u, (cy + 4) * u, stroke); c.drawLine((cx + 20) * u, (cy - 12) * u, cx * u, (cy + 4) * u, stroke)
                }
                BadgeKind.NOTES -> { // a bell
                    val bell = android.graphics.Path().apply {
                        moveTo((cx - 16) * u, (cy + 12) * u); lineTo((cx - 12) * u, (cy + 12) * u); lineTo((cx - 12) * u, (cy - 4) * u)
                        quadTo((cx - 12) * u, (cy - 18) * u, cx * u, (cy - 18) * u); quadTo((cx + 12) * u, (cy - 18) * u, (cx + 12) * u, (cy - 4) * u)
                        lineTo((cx + 12) * u, (cy + 12) * u); lineTo((cx + 16) * u, (cy + 12) * u)
                    }
                    c.drawPath(bell, stroke); c.drawLine((cx - 16) * u, (cy + 12) * u, (cx + 16) * u, (cy + 12) * u, stroke)
                    c.drawCircle(cx * u, (cy + 17) * u, 3f * u, fill)
                }
                BadgeKind.MISSED -> { // a handset, drawn as an arc with a blob at each end
                    stroke.strokeWidth = 7 * u
                    c.drawArc(RectF((cx - 22) * u, (cy - 6) * u, (cx + 22) * u, (cy + 38) * u), 200f, 140f, false, stroke)
                    fill.color = ink
                    c.drawRect((cx - 24) * u, (cy + 2) * u, (cx - 12) * u, (cy + 12) * u, fill); c.drawRect((cx + 12) * u, (cy + 2) * u, (cx + 24) * u, (cy + 12) * u, fill)
                    stroke.strokeWidth = 3 * u
                }
            }
        }

        /** One weather symbol as an outline: a filled shape in the ink colour with the same shape inset in the screen colour. */
        private fun cloud(c: Canvas, cx: Float, cy: Float, s: Float, u: Float, color: Int, grow: Float) {
            fill.color = color
            c.drawCircle((cx - 0.26f * s) * u, (cy + 0.06f * s) * u, (0.2f * s + grow) * u, fill)
            c.drawCircle((cx - 0.02f * s) * u, (cy - 0.1f * s) * u, (0.28f * s + grow) * u, fill)
            c.drawCircle((cx + 0.27f * s) * u, (cy + 0.08f * s) * u, (0.19f * s + grow) * u, fill)
            c.drawRect((cx - 0.26f * s) * u, (cy + 0.06f * s - grow) * u, (cx + 0.27f * s) * u, (cy + 0.27f * s + grow) * u, fill)
            fill.color = ink
        }

        private fun sun(c: Canvas, cx: Float, cy: Float, r: Float, u: Float) {
            stroke.strokeWidth = 3 * u
            c.drawCircle(cx * u, cy * u, r * u, stroke)
            for (k in 0 until 8) {
                val a = Math.PI * k / 4
                c.drawLine((cx + Math.cos(a) * r * 1.4).toFloat() * u, (cy + Math.sin(a) * r * 1.4).toFloat() * u, (cx + Math.cos(a) * r * 1.9).toFloat() * u, (cy + Math.sin(a) * r * 1.9).toFloat() * u, stroke)
            }
        }

        private fun weatherIcon(c: Canvas, kind: WeatherIcon, cx: Float, cy: Float, s: Float, u: Float) {
            stroke.strokeWidth = 3 * u
            fun outlineCloud(x: Float, y: Float, size: Float) { cloud(c, x, y, size, u, ink, 2.5f); cloud(c, x, y, size, u, lcd, 0f) }
            when (kind) {
                WeatherIcon.SUN -> sun(c, cx, cy, s * 0.22f, u)
                WeatherIcon.MOON -> { fill.color = ink; c.drawCircle(cx * u, cy * u, s * 0.3f * u, fill); fill.color = lcd; c.drawCircle((cx + s * 0.16f) * u, (cy - s * 0.1f) * u, s * 0.27f * u, fill); fill.color = ink }
                WeatherIcon.PARTLY -> { sun(c, cx - s * 0.15f, cy - s * 0.15f, s * 0.14f, u); outlineCloud(cx + s * 0.05f, cy + s * 0.08f, s * 0.8f) }
                WeatherIcon.CLOUD -> outlineCloud(cx, cy, s)
                WeatherIcon.FOG -> for (k in 0 until 4) c.drawLine((cx - s * 0.35f + (k % 2) * s * 0.1f) * u, (cy - s * 0.25f + k * s * 0.17f) * u, (cx + s * 0.35f + (k % 2) * s * 0.1f) * u, (cy - s * 0.25f + k * s * 0.17f) * u, stroke)
                WeatherIcon.RAIN -> { outlineCloud(cx, cy - s * 0.12f, s * 0.9f); for (k in 0 until 3) { val x = cx - s * 0.22f + k * s * 0.22f; c.drawLine(x * u, (cy + s * 0.2f) * u, (x - s * 0.07f) * u, (cy + s * 0.38f) * u, stroke) } }
                WeatherIcon.SNOW -> { outlineCloud(cx, cy - s * 0.12f, s * 0.9f); for (k in 0 until 3) c.drawCircle((cx - s * 0.22f + k * s * 0.22f) * u, (cy + s * 0.3f) * u, 2.5f * u, fill) }
                WeatherIcon.STORM -> {
                    outlineCloud(cx, cy - s * 0.12f, s * 0.9f)
                    val b = android.graphics.Path().apply { moveTo((cx + s * 0.05f) * u, (cy + s * 0.12f) * u); lineTo((cx - s * 0.1f) * u, (cy + s * 0.3f) * u); lineTo((cx + s * 0.03f) * u, (cy + s * 0.3f) * u); lineTo((cx - s * 0.05f) * u, (cy + s * 0.45f) * u) }
                    c.drawPath(b, stroke)
                }
            }
        }

        /** The first glyph's ink starts at x (design pixels): a bold 36 "1" and a regular 24 "8" have different side bearings, so each line is nudged to make the ink edges meet. */
        private fun textInk(c: Canvas, s: String, x: Float, y: Float, size: Float, u: Float, bold: Boolean = false) {
            p.typeface = faces[TEXT] ?: Typeface.MONOSPACE; p.textSize = size * scaleOf(TEXT) * u; p.isFakeBoldText = bold
            p.textAlign = Paint.Align.LEFT; p.letterSpacing = 0f // the outline moves with the alignment left over from the last text drawn (a centred date made it start 16 px too far right)
            val path = android.graphics.Path(); p.getTextPath(s, 0, 1.coerceAtMost(s.length), 0f, 0f, path)
            val r = RectF(); path.computeBounds(r, true)
            text(c, s, x - r.left / u, y, size, u, bold = bold)
        }

        /**
         * The weather band on the home screen with its centre line at [cy]: one group centred on the screen's axis: the current weather (symbol, temperature, high and low) and, after
         * a spacer, rain due (symbol, time and chance) or "Dry". Without a forecast yet, just the current weather.
         */
        private val HL_SIZE = 20f

        private fun weatherStrip(c: Canvas, w: WeatherData, cy: Float, m0time: String, u: Float) {
            val temp = "${w.temp}°"
            val band = w.rainBand()
            val axis = BandGeometry.divider(clockColonX(m0time, u)) // measured first: it leaves the shared paint at the clock's size
            val hl = w.highLow()                                    // today's high and low, stacked, belong with the current temperature
            val hw = hl?.let { maxOf(inkW(it.first, HL_SIZE, false, u), inkW(it.second, HL_SIZE, false, u)) } ?: 0f
            val rightW = band?.let { b -> BandGeometry.rightWidth(b.due, maxOf(inkW(b.line1, 36f, true, u), if (b.line2.isNotEmpty()) inkW(b.line2, 24f, false, u) else 0f)) } ?: 0f
            val ts = BandGeometry.tempSize(inkW(temp, 46f, true, u), BandGeometry.leftWidth(0f, hw) + BandGeometry.groupWidth(0f, rightW))
            p.typeface = faces[TEXT] ?: Typeface.MONOSPACE; p.textSize = ts * scaleOf(TEXT) * u; p.isFakeBoldText = true
            val rb = inkBounds(temp, u)
            // one group, centred on the axis: [symbol temperature H/L] spacer [rain or dry]; no divider line
            val leftW = BandGeometry.leftWidth(rb.width(), hw)
            val gl = BandGeometry.groupLeft(axis, leftW, rightW)
            weatherIcon(c, w.icon, gl + BandGeometry.SYMBOL / 2, cy, BandGeometry.SYMBOL, u)
            val tempLeft = gl + BandGeometry.SYMBOL + BandGeometry.SYMBOL_GAP                               // the temperature's INK starts here
            text(c, temp, tempLeft - rb.left, cy + 18f, ts, u, bold = true)
            hl?.let { val sx = tempLeft + rb.width() + BandGeometry.STACK_GAP; textInk(c, it.first, sx, cy - 5f, HL_SIZE, u); textInk(c, it.second, sx, cy + 19f, HL_SIZE, u) }
            if (band == null) return
            val rs = gl + leftW + BandGeometry.WEATHER_SPACER
            val x = if (band.due) rs + BandGeometry.RAIN_ICON + BandGeometry.TEXT_AFTER_ICON else rs
            if (band.due) band.icon?.let { weatherIcon(c, it, rs + BandGeometry.RAIN_ICON / 2, cy, BandGeometry.RAIN_ICON, u) }
            if (band.line2.isNotEmpty()) { textInk(c, band.line1, x, cy, 36f, u, bold = true); textInk(c, band.line2, x, cy + 26f, 24f, u) }
            else textInk(c, band.line1, x, cy + 13f, 36f, u, bold = true)                                   // a single line ("Dry") sits on the band's middle
        }

        /** The ink width of [s] at [size] (design pixels). */
        private fun inkW(s: String, size: Float, bold: Boolean, u: Float): Float {
            p.typeface = faces[TEXT] ?: Typeface.MONOSPACE; p.textSize = size * scaleOf(TEXT) * u; p.isFakeBoldText = bold
            return inkBounds(s, u).width()
        }

        /** The exact ink box of [s] with the paint as it is now, in design pixels relative to the text origin (the outline, not the rounded text bounds). */
        private fun inkBounds(s: String, u: Float): RectF {
            p.textAlign = Paint.Align.LEFT; p.letterSpacing = 0f
            val path = android.graphics.Path(); p.getTextPath(s, 0, s.length, 0f, 0f, path)
            val r = RectF(); path.computeBounds(r, true)
            return RectF(r.left / u, r.top / u, r.right / u, r.bottom / u)
        }

        /** The clock's colon centre (design pixels) as the clock is drawn centred at x=240, or null when it cannot be measured. */
        private fun clockColonX(time: String, u: Float): Float? {
            val i = time.indexOf(':'); if (i <= 0) return null
            val fc = choices[DISPLAY]
            p.typeface = faces[DISPLAY] ?: Typeface.MONOSPACE; p.textSize = 120f * scaleOf(DISPLAY) * u; p.isFakeBoldText = true; p.letterSpacing = fc.tracking; p.textAlign = Paint.Align.LEFT
            val whole = p.measureText(time); val before = p.measureText(time.substring(0, i))
            val colon = RectF().also { r -> val path = android.graphics.Path(); p.getTextPath(":", 0, 1, 0f, 0f, path); path.computeBounds(r, true) }
            p.letterSpacing = 0f
            return (240f * u - whole / 2 + before + (colon.left + colon.right) / 2) / u
        }

        /**
         * The weather (above the clock, or lower in the Mac look) and zone 5, the strip where the weather used to be: one page at a time, music (a play or pause
         * mark, one scrolling "Artist - Title" line and a progress bar) and then the next event. More than one page shows dots under it, and a change of page
         * slides the old one off and the new one on.
         */
        private fun homeLower(c: Canvas, m: LauncherModel, i: HomeInfo, u: Float) {
            val mac = Themes.byId(m.theme).skin == "mac"
            val slot = homeSlotY(mac)
            m.weather?.let { weatherStrip(c, it, weatherCy(mac), i.time, u) }
            val pages = m.nowPages
            if (pages.isEmpty()) return
            val cur = pages[m.nowPage.coerceIn(0, pages.size - 1)]
            val slide = m.nowSlide()
            if (slide == null) nowPageBody(c, cur, i, slot, u)
            else {
                val (from, dir, t) = slide
                val e = 1f - (1f - t) * (1f - t)
                val w = 464f
                c.save(); c.clipRect(8f * u, NOW_TOP * u, 472f * u, NOW_BOTTOM * u)
                c.save(); c.translate(-dir * w * e * u, 0f); nowPageBody(c, from, i, slot, u); c.restore()
                c.save(); c.translate(dir * w * (1f - e) * u, 0f); nowPageBody(c, cur, i, slot, u); c.restore()
                c.restore(); postInvalidateDelayed(16)
            }
            if (pages.size > 1) { // one dot a page, the one showing filled
                val x0 = 240f - (pages.size - 1) * 9f
                stroke.strokeWidth = 2f * u; stroke.color = ink; fill.color = ink
                pages.indices.forEach { k ->
                    val cx = (x0 + k * 18f) * u; val cy = NOW_DOTS_Y * u
                    if (k == m.nowPage) { fill.style = Paint.Style.FILL; c.drawCircle(cx, cy, 4.5f * u, fill) } else c.drawCircle(cx, cy, 3.5f * u, stroke)
                }
            }
        }

        private fun nowPageBody(c: Canvas, page: String, i: HomeInfo, slot: Float, u: Float) {
            when (page) {
                "music" -> i.nowPlaying?.let { nowPlayingLine(c, it, slot, u) }
                "event" -> if (i.nextEvents.isNotEmpty()) {
                    // the calendar mark at the left, centred on the block; one event a line, the same size and weight
                    calendarIcon(c, 24f, 411f, u)
                    nowLines(c, i.nextEvents.map { NowLine(it.title, NOW_SIZE, NOW_BOLD, it.prefix) }, 72f, 384f, u)
                }
            }
        }

        /**
         * One or two lines in the strip (line 1 regular, line 2 bold); a lone line sits halfway between them. When a line is too long the lines scroll
         * together as [NOW_SYNC_SCROLL] and [NOW_SHORT_STAYS] say: rest, travel (the longer line's overflow), rest, back.
         */
        private fun nowLines(c: Canvas, first: String, second: String, x: Float, room: Float, u: Float) {
            val two = first.isNotBlank() && second.isNotBlank()
            if (two && !NOW_SYNC_SCROLL) { text(c, ellipsize(first, room, NOW_SIZE), x, NOW_LINE1, NOW_SIZE, u, bold = NOW_BOLD); marquee(c, second, x, NOW_LINE2, NOW_SIZE, room, u, bold = NOW_BOLD); return }
            nowLines(c, listOfNotNull(first.takeIf { it.isNotBlank() }, second.takeIf { it.isNotBlank() }).map { NowLine(it, NOW_SIZE, NOW_BOLD) }, x, room, u)
        }

        private fun measureText(t: String, size: Float, bold: Boolean): Float { val paint = bodyPaint(); paint.textSize = size * scaleOf(TEXT); paint.isFakeBoldText = bold; val w = paint.measureText(t); paint.isFakeBoldText = false; return w }
        private fun measureLine(l: NowLine): Float = measureText(l.fixed + l.s, l.size, l.bold)

        /**
         * One or two lines at the strip's two baselines (a lone line halfway between). A line scrolls only when it is too long for its room; one that fits stays still.
         * When both scroll they move together (one offset and timing). A line's [NowLine.fixed] start (an event's time) stays put and only the rest scrolls beside it.
         */
        private fun nowLines(c: Canvas, lines: List<NowLine>, x: Float, room: Float, u: Float) {
            if (lines.isEmpty()) return
            val ys = if (lines.size == 1) listOf(NOW_LONE) else listOf(NOW_LINE1, NOW_LINE2)
            val fw = lines.map { if (it.fixed.isEmpty()) 0f else measureText(it.fixed, it.size, it.bold) }
            val shown = lines.mapIndexed { n, l -> if (measureText(l.s, l.size, l.bold) > (room - fw[n]) * 3) NowLine(ellipsize(l.s, (room - fw[n]) * 3, l.size), l.size, l.bold, l.fixed) else l } // never longer than three screens
            val over = shown.mapIndexed { n, l -> (measureText(l.s, l.size, l.bold) - (room - fw[n])).coerceAtLeast(0f) }
            val own = over.max()
            frameTravel = maxOf(frameTravel, own)
            val travel = maxOf(syncTravel, own)
            val off = ScrollClock.offset(travel, System.currentTimeMillis())
            shown.forEachIndexed { n, l ->
                val y = ys[n]
                if (l.fixed.isNotEmpty()) text(c, l.fixed, x, y, l.size, u, bold = l.bold)
                val tx = x + fw[n]
                if (over[n] <= 0f) { text(c, l.s, tx, y, l.size, u, bold = l.bold); return@forEachIndexed } // it fits: it stays still
                val o = if (NOW_SHORT_STAYS) minOf(off, over[n]) else off
                c.save(); c.clipRect(tx * u, (y - l.size * 1.1f) * u, (x + room) * u, (y + l.size * 0.4f) * u); text(c, l.s, tx - o, y, l.size, u, bold = l.bold); c.restore()
            }
            if (own > 0f) postInvalidateDelayed(60)
        }

        private fun nowPlayingLine(c: Canvas, np: io.github.durkimat.phoneguard.music.MusicInfo, top: Float, u: Float) {
            fill.color = ink; fill.style = Paint.Style.FILL
            if (np.playing) {
                val tri = android.graphics.Path().apply { moveTo(24f * u, (top - 14f) * u); lineTo(48f * u, top * u); lineTo(24f * u, (top + 14f) * u); close() }
                c.drawPath(tri, fill)
            } else { c.drawRect(26f * u, (top - 12f) * u, 33f * u, (top + 12f) * u, fill); c.drawRect(40f * u, (top - 12f) * u, 47f * u, (top + 12f) * u, fill) }
            // the artist over the title; a radio shows the station over what is on now
            if (np.source == "radio") {
                // the station, with the artist after it when "station - artist" fits on the line; the song below (title = song, artist = artist, the same fields as the Now playing screen)
                val both = if (np.artist.isNotEmpty()) "${np.station} - ${np.artist}" else ""
                val line1 = if (both.isNotEmpty() && measureLine(NowLine(both, NOW_SIZE, NOW_BOLD)) <= 392f) both else np.station
                nowLines(c, line1, np.title, 64f, 392f, u)
            } else nowLines(c, np.artist, np.title, 64f, 392f, u)
            if (np.durationMs > 0) {
                stroke.strokeWidth = 2f * u; stroke.color = ink
                c.drawRect(24f * u, NOW_BAR_TOP * u, 456f * u, (NOW_BAR_TOP + 8f) * u, stroke)
                c.drawRect(24f * u, NOW_BAR_TOP * u, (24f + 432f * (np.positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f)) * u, (NOW_BAR_TOP + 8f) * u, fill)
            }
        }

        /** A calendar page in a 36 x 32 box (left edge [x0], vertical centre [cy]): outline, filled header, two binder ticks and a day grid whose last square is empty. */
        private fun calendarIcon(c: Canvas, x0: Float, cy: Float, u: Float) {
            val y0 = cy - 16f
            fill.color = ink; fill.style = Paint.Style.FILL; stroke.color = ink; stroke.strokeWidth = 2.5f * u
            c.drawRoundRect(RectF((x0 + 2f) * u, (y0 + 5f) * u, (x0 + 34f) * u, (y0 + 30f) * u), 3f * u, 3f * u, stroke)
            c.drawRect((x0 + 2f) * u, (y0 + 5f) * u, (x0 + 34f) * u, (y0 + 11f) * u, fill)
            c.drawRect((x0 + 9f) * u, (y0 + 1f) * u, (x0 + 12f) * u, (y0 + 9f) * u, fill)
            c.drawRect((x0 + 25f) * u, (y0 + 1f) * u, (x0 + 28f) * u, (y0 + 9f) * u, fill)
            for ((gx, gy) in listOf(8f to 16f, 16f to 16f, 24f to 16f, 8f to 23f, 16f to 23f)) c.drawRect((x0 + gx) * u, (y0 + gy) * u, (x0 + gx + 5f) * u, (y0 + gy + 4f) * u, fill)
        }

        private fun musicScreen(c: Canvas, m: LauncherModel, u: Float) {
            titleBar(c, m.title, u)
            val mu = m.music
            val radio = m.provider == Provider.RADIO
            if (!mu.active) {
                text(c, "Nothing playing", 240f, 230f, 32f, u, bold = true, align = Paint.Align.CENTER)
                val other = m.playingProvider?.takeIf { it != m.provider }
                text(c, if (other != null) (if (other == Provider.RADIO) "Internet Radio" else "Spotify") + " is playing"
                    else if (radio) (if (m.radioNames.isEmpty()) "No stations in this mode" else "OK picks a station")
                    else if (m.spSignedIn) "OK picks a playlist" else "Menu: sign in to Spotify", 240f, 280f, 26f, u, align = Paint.Align.CENTER)
                if (other != null) text(c, if (radio) "OK picks a station" else if (m.spSignedIn) "OK picks a playlist" else "", 240f, 316f, 24f, u, align = Paint.Align.CENTER)
            } else if (chrome.nowPlaying(this, c, m, u)) {
                // the chrome drew the whole player
            } else {
                if (radio) text(c, fit(mu.station.ifEmpty { "Internet Radio" } + (if (m.radioIndex >= 0) "  " + (m.radioIndex + 1) else ""), if (m.sleepLabel.isNotEmpty()) 330f else 440f, 24f), 20f, 134f, 24f, u)
                else if (mu.device.isNotEmpty()) text(c, if (mu.thisPhone) "Playing here" else ellipsize("on " + mu.device, 440f, 24f), 20f, 134f, 24f, u)
                marquee(c, mu.title.ifEmpty { if (radio && mu.station.isNotEmpty()) mu.station else "(no title)" }, 20f, 190f, 40f, 440f, u, bold = true)
                marquee(c, mu.artist, 20f, 240f, 32f, 440f, u)
                // where it is playing from (a playlist), else the album unless that only repeats the song or artist
                val sub = if (mu.from.isNotEmpty()) mu.from else if (mu.album.isNotEmpty() && !mu.album.equals(mu.title, true) && !mu.album.equals(mu.artist, true)) mu.album else ""
                if (sub.isNotEmpty()) marquee(c, sub, 20f, 284f, 26f, 440f, u)
                // progress
                if (m.spectrum) spectrum(c, mu.playing && mu.thisPhone, u)
                else if (radio) text(c, "LIVE", 240f, 352f, 40f, u, bold = true, align = Paint.Align.CENTER)
                else {
                    stroke.strokeWidth = 3 * u
                    c.drawRect(20 * u, 332 * u, 460 * u, 356 * u, stroke)
                    if (mu.durationMs > 0) c.drawRect(24 * u, 336 * u, (24 + 432f * mu.positionMs / mu.durationMs) * u, 352 * u, fill)
                }
                text(c, io.github.durkimat.phoneguard.music.MusicInfo.clock(mu.positionMs), 20f, 392f, 26f, u)
                if (radio) text(c, "LIVE", 460f, 392f, 26f, u, bold = true, align = Paint.Align.RIGHT)
                if (m.spectrum && mu.durationMs > 0) { // the spectrum took the bar's place, so a slim progress bar sits between the two times
                    stroke.strokeWidth = 2 * u
                    c.drawRect(100 * u, 376 * u, 380 * u, 390 * u, stroke)
                    c.drawRect(103 * u, 379 * u, (103 + 274f * mu.positionMs / mu.durationMs) * u, 387 * u, fill)
                }
                if (mu.durationMs > 0) text(c, io.github.durkimat.phoneguard.music.MusicInfo.clock(mu.durationMs), 460f, 392f, 26f, u, align = Paint.Align.RIGHT)
                // the state: a large play triangle or two pause bars, between the previous and next hints
                if (mu.playing) { c.drawRect(216 * u, 424 * u, 234 * u, 484 * u, fill); c.drawRect(246 * u, 424 * u, 264 * u, 484 * u, fill) }
                else { val t = android.graphics.Path().apply { moveTo(216f * u, 422f * u); lineTo(268f * u, 454f * u); lineTo(216f * u, 486f * u); close() }; c.drawPath(t, fill) }
                if (radio) { text(c, "< prev", 20f, 464f, 26f, u); text(c, "next >", 460f, 464f, 26f, u, align = Paint.Align.RIGHT) }
                else { text(c, "4 prev", 20f, 464f, 26f, u); text(c, "6 next", 460f, 464f, 26f, u, align = Paint.Align.RIGHT) }
                if (radio) {
                    text(c, if (m.radioMode == "any") "1-9 presets, hold to set" else "1-9 presets", 240f, 506f, 24f, u, align = Paint.Align.CENTER)
                    text(c, "* bars  0 sleep  # source", 240f, 536f, 24f, u, align = Paint.Align.CENTER)
                    text(c, "Hold OK: stop", 240f, 564f, 22f, u, align = Paint.Align.CENTER)
                    if (m.sleepLabel.isNotEmpty()) text(c, "sleep " + m.sleepLabel, 460f, 134f, 24f, u, align = Paint.Align.RIGHT)
                } else {
                    if (m.spSignedIn) text(c, "* shuffle " + (if (mu.shuffle) "on" else "off") + "   1 repeat " + (when (mu.repeat) { "context" -> "all"; "track" -> "one"; else -> "off" }), 240f, 506f, 24f, u, align = Paint.Align.CENTER)
                    text(c, "7 bars  0 sleep  # source", 240f, 536f, 24f, u, align = Paint.Align.CENTER)
                    text(c, "Hold OK: stop", 240f, 564f, 22f, u, align = Paint.Align.CENTER)
                    if (m.sleepLabel.isNotEmpty()) text(c, "sleep " + m.sleepLabel, 460f, 134f, 24f, u, align = Paint.Align.RIGHT)
                }
            }
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        /** Classic spectrum analyser in the progress bar's place: bouncing bars built of small blocks, each with a held peak block above it. */
        override fun spectrum(c: Canvas, live: Boolean, u: Float) {
            val sp = io.github.durkimat.phoneguard.music.Spectrum
            if (live) sp.use(context) else sp.stop() // not playing here: let go and show empty bars
            val n = sp.BARS; val left = 24f; val barW = 23f; val gap = 4f
            val bottom = 364f; val blocks = 9; val blockH = 5f; val pitch = 7f
            for (i in 0 until n) {
                val x = left + i * (barW + gap)
                val lit = (sp.level[i] * blocks).toInt()
                for (b in 0 until blocks) {
                    val y = bottom - (b + 1) * pitch
                    if (b < lit) { fill.color = if (style.bars == "grad") Palette.climb(night, b / (blocks - 1f)) else ink; c.drawRect(x * u, y * u, (x + barW) * u, (y + blockH) * u, fill); fill.color = ink }
                    else stroke.also { it.strokeWidth = 1 * u }.let { if (b == 0) c.drawRect(x * u, y * u, (x + barW) * u, (y + blockH) * u, it) }
                }
                val pk = (sp.peak[i] * blocks).toInt().coerceIn(0, blocks - 1)
                if (style.bars != "none" && pk >= lit && sp.peak[i] > 0.05f) { val y = bottom - (pk + 1) * pitch; fill.color = when (style.bars) { "same" -> ink; "grad" -> Palette.climb(night, pk / (blocks - 1f)); else -> accent }; c.drawRect(x * u, y * u, (x + barW) * u, (y + blockH) * u, fill); fill.color = ink }
            }
            if (live) postInvalidateDelayed(70)
        }

        /**
         * A line that fits is drawn plainly. A longer one rests at its start, then scrolls left as a loop: a gap follows the end
         * and the start comes back in behind it, so when the scroll stops the line is exactly where it began (no jump).
         */
        override fun marquee(c: Canvas, s0: String, x: Float, y: Float, size: Float, room: Float, u: Float, bold: Boolean, color: Int, center: Boolean, role: Int) {
            var s = s0
            if (measureBold(s, size, role) > room * 3) s = ellipsize(s, room * 3, size) // never longer than three screens
            val w = measureBold(s, size, role)
            if (w <= room) { if (center) text(c, s, x + room / 2, y, size, u, bold = bold, align = Paint.Align.CENTER, color = color, role = role) else text(c, s, x, y, size, u, bold = bold, color = color, role = role); return }
            // a line scrolls only when it must, and every scrolling line in the frame moves together: one clock, the longest overflow
            val over = w - room
            frameTravel = maxOf(frameTravel, over)
            val off = ScrollClock.offset(maxOf(syncTravel, over), System.currentTimeMillis()).let { if (NOW_SHORT_STAYS) minOf(it, over) else it }
            c.save(); c.clipRect(x * u, (y - size * 1.1f) * u, (x + room) * u, (y + size * 0.4f) * u)
            text(c, s, x - off, y, size, u, bold = bold, color = color, role = role)
            c.restore()
            postInvalidateDelayed(60)
        }

        /** The title bar's text: centred when it fits, otherwise it scrolls forwards and back. */
        /** The title bar: an inverted band for the Nokia look; a Mac window notes the title and draws its own bar around the content. */
        private fun titleBar(c: Canvas, s: String, u: Float) {
            if (chrome.title(this, c, s, u)) return
            c.drawRect(0f, 47 * u, width.toFloat(), 99 * u, fill)
            titleText(c, s, u)
        }

        private fun titleText(c: Canvas, s: String, u: Float) = marquee(c, s, 20f, 85f, 30f, 440f, u, bold = true, color = lcd, center = true, role = LABEL)

        private fun noteScreen(c: Canvas, m: LauncherModel, u: Float) {
            titleBar(c, m.title, u)
            val n = m.currentNote
            if (n == null) text(c, "Gone", 240f, 300f, 30f, u, align = Paint.Align.CENTER)
            else {
                text(c, ellipsize(n.app, 440f, 28f), 20f, 140f, 28f, u, bold = true)
                var y = 184f
                for (line in m.wrapper(if (n.title.isNotEmpty()) n.title + "\n" + n.text else n.text).take(10)) { text(c, line, 20f, y, 26f, u); y += 36f }
                text(c, "0 clear", 464f, 560f, 24f, u, align = Paint.Align.RIGHT)
            }
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun timerScreen(c: Canvas, m: LauncherModel, u: Float) {
            titleBar(c, m.title, u)
            val t = m.timer
            val left = t.leftSec(io.github.durkimat.phoneguard.Clock.now())
            text(c, io.github.durkimat.phoneguard.alarm.AlarmMath.mmss(left.toLong()), 240f, 290f, 110f, u, bold = true, align = Paint.Align.CENTER, role = DISPLAY)
            text(c, if (t.running) "Running" else if (t.paused) "Paused" else "Set the time", 240f, 350f, 30f, u, bold = true, align = Paint.Align.CENTER)
            stroke.strokeWidth = 2 * u; c.drawLine(16 * u, 392 * u, 464 * u, 392 * u, stroke)
            val hints = if (!t.running && !t.paused) listOf("Up, down: minutes", "Left, right: 10 seconds", "1 to 9: that many minutes") else listOf("* resets the timer", "Back leaves it running")
            hints.forEachIndexed { n, h -> text(c, h, 240f, 436f + n * 36f, 26f, u, align = Paint.Align.CENTER) }
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun stopwatchScreen(c: Canvas, m: LauncherModel, u: Float) {
            titleBar(c, m.title, u)
            val s = m.stopwatch
            val now = io.github.durkimat.phoneguard.Clock.now()
            text(c, s.text(now), 240f, 230f, 84f, u, bold = true, align = Paint.Align.CENTER, role = DISPLAY)
            stroke.strokeWidth = 2 * u; c.drawLine(16 * u, 262 * u, 464 * u, 262 * u, stroke)
            val laps = s.laps
            laps.withIndex().toList().takeLast(5).reversed().forEachIndexed { n, (i, e) ->
                val prev = if (i == 0) 0L else laps[i - 1]
                text(c, "Lap ${i + 1}", 20f, 304f + n * 40f, 26f, u)
                text(c, io.github.durkimat.phoneguard.alarm.AlarmMath.mmss((e - prev) / 1000) + "." + ((e - prev) % 1000 / 100), 464f, 304f + n * 40f, 26f, u, align = Paint.Align.RIGHT)
            }
            if (laps.isEmpty()) text(c, if (s.running) "Down: lap" else "* resets", 240f, 310f, 26f, u, align = Paint.Align.CENTER)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun ringScreen(c: Canvas, m: LauncherModel, u: Float) {
            titleBar(c, m.title, u)
            text(c, m.ringTime, 240f, 270f, if (m.ringKind == "timer") 80f else 120f, u, bold = true, align = Paint.Align.CENTER, role = DISPLAY)
            text(c, if (m.ringKind == "timer") "Time is up" else "Snooze: ${io.github.durkimat.phoneguard.alarm.AlarmMath.SNOOZE_MIN} minutes", 240f, 340f, 30f, u, bold = true, align = Paint.Align.CENTER)
            if (m.ringQuiet) text(c, "Quiet while on a call", 240f, 390f, 26f, u, align = Paint.Align.CENTER)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun batteryScreen(c: Canvas, m: LauncherModel, u: Float) {
            titleBar(c, m.title, u)
            val b = m.battery
            if (b == null) text(c, "Reading...", 240f, 300f, 30f, u, align = Paint.Align.CENTER)
            else {
                // a wide battery with a nub, filled to the level
                stroke.strokeWidth = 4 * u
                c.drawRect(60 * u, 130 * u, 400 * u, 210 * u, stroke); c.drawRect(400 * u, 155 * u, 416 * u, 185 * u, fill)
                c.drawRect(68 * u, 138 * u, (68 + 324f * b.level / 100f) * u, 202 * u, fill)
                text(c, "${b.level}%" + if (b.charging) " charging" else "", 240f, 262f, 40f, u, bold = true, align = Paint.Align.CENTER)
                b.hoursLeft?.let { h -> text(c, "About " + (if (h >= 48) "${(h / 24).toInt()} days" else "${h.toInt()} h ${((h - h.toInt()) * 60).toInt()} min") + " left", 240f, 306f, 28f, u, align = Paint.Align.CENTER) }
                stroke.strokeWidth = 2 * u; c.drawLine(16 * u, 330 * u, 464 * u, 330 * u, stroke)
                text(c, "Screen today  " + (if (b.screenMinutes >= 60) "${b.screenMinutes / 60} h " else "") + "${b.screenMinutes % 60} min", 20f, 372f, 26f, u, bold = true)
                b.top.forEachIndexed { n, (name, min) ->
                    text(c, ellipsize(name, 300f, 26f), 20f, 416f + n * 40f, 26f, u)
                    text(c, "$min min", 464f, 416f + n * 40f, 26f, u, align = Paint.Align.RIGHT)
                }
            }
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun weatherScreen(c: Canvas, m: LauncherModel, u: Float) {
            titleBar(c, m.title, u)
            val w = m.weather
            if (w == null) { text(c, "No weather yet", 240f, 300f, 30f, u, align = Paint.Align.CENTER) }
            else {
                weatherIcon(c, w.icon, 70f, 160f, 80f, u)
                text(c, "${w.temp}°", 132f, 176f, 56f, u, bold = true)
                text(c, w.description, 464f, 160f, 26f, u, align = Paint.Align.RIGHT)
                w.hint()?.let { text(c, it, 464f, 196f, 24f, u, bold = true, align = Paint.Align.RIGHT) }
                stroke.strokeWidth = 2 * u; c.drawLine(16 * u, 226 * u, 464 * u, 226 * u, stroke)
                w.hours.drop(1).take(5).forEachIndexed { n, h ->
                    text(c, h.time, 20f, 262f + n * 32f, 24f, u)
                    text(c, "${h.temp}°", 150f, 262f + n * 32f, 24f, u)
                    text(c, WeatherData.describe(h.code) + if (h.prob >= 20) " ${h.prob}%" else "", 464f, 262f + n * 32f, 22f, u, align = Paint.Align.RIGHT)
                }
                c.drawLine(16 * u, 432 * u, 464 * u, 432 * u, stroke)
                w.days.take(3).forEachIndexed { n, d ->
                    text(c, d.label, 20f, 468f + n * 32f, 24f, u)
                    text(c, "${d.min}° to ${d.max}°", 150f, 468f + n * 32f, 24f, u)
                    text(c, WeatherData.describe(d.code), 464f, 468f + n * 32f, 22f, u, align = Paint.Align.RIGHT)
                }
                text(c, "Weather data by Open-Meteo.com", 240f, 570f, 18f, u, align = Paint.Align.CENTER)
            }
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        /** Mobile data on the home screen, one line under the status strip: what is left in words and the share used as a bar; at 10% or less left, an inverted banner instead. */
        private fun dataWidget(c: Canvas, v: io.github.durkimat.phoneguard.core.DataView, time: String, u: Float) {
            val y = dataY(Themes.byId(model?.theme).skin == "mac") // the Mac look has a title bar and a frame: the line sits just inside it
            if (v.low) {
                fill.color = ink; c.drawRect(14f * u, (60f + y) * u, 466f * u, (88f + y) * u, fill)
                text(c, ellipsize(v.bannerText, 440f, 24f), 240f, 83f + y, 24f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
                return
            }
            val div = BandGeometry.divider(clockColonX(time, u)) // measured first: it sets the paint to the clock's size, so the words' paint comes after it
            p.typeface = faces[TEXT] ?: Typeface.MONOSPACE; p.isFakeBoldText = true; p.textAlign = Paint.Align.LEFT; p.letterSpacing = 0f
            p.textSize = BandGeometry.DATA_TEXT_SIZE * scaleOf(TEXT) * u
            var rb = inkBounds(v.leftText, u)
            val size = BandGeometry.dataTextSize(rb.width(), BandGeometry.DATA_WORDS_ROOM)
            if (size != BandGeometry.DATA_TEXT_SIZE) { p.textSize = size * scaleOf(TEXT) * u; rb = inkBounds(v.leftText, u) }
            // one group centred on the axis: [words] spacer [bar]
            val gl = BandGeometry.dataGroupLeft(div, rb.width())
            text(c, v.leftText, gl - rb.left, 84f + y, size, u, bold = true)
            val bl = BandGeometry.dataBarLeft(gl, rb.width()); val br = bl + BandGeometry.DATA_BAR_W
            stroke.strokeWidth = 2f * u; stroke.color = ink
            c.drawRect(bl * u, (63f + y) * u, br * u, (85f + y) * u, stroke)
            fill.color = ink; c.drawRect(bl * u, (63f + y) * u, (bl + BandGeometry.DATA_BAR_W * v.fraction) * u, (85f + y) * u, fill)
        }

        private fun home(c: Canvas, i: HomeInfo, u: Float) {
            // deliberately no mode name or banner: in School this must look like any plain phone
            text(c, i.time, 240f, clockY(Themes.byId(model?.theme).skin == "mac"), 120f, u, bold = true, align = Paint.Align.CENTER, role = DISPLAY)
            text(c, i.date, 240f + BandGeometry.DATE_NUDGE + BandGeometry.axisShift(BandGeometry.divider(clockColonX(i.time, u))), if ((i.alarmText.isNotEmpty() || timerLeft(i) > 0) && Themes.byId(model?.theme).skin == "mac") 352f else if (Themes.byId(model?.theme).skin == "mac") 332f else 336f, 36f, u, align = Paint.Align.CENTER) // on the axis, with the divider and the data bar
            // just under the clock: a bell and the next alarm's time, and a stopwatch face and the timer's time left, nothing else.
            // Both go on one line when they fit (the long-digit fonts may not), otherwise one under the other.
            val left = timerLeft(i)
            if (i.alarmText.isNotEmpty() || left > 0) {
                val mac = Themes.byId(model?.theme).skin == "mac"
                p.typeface = faces[TEXT] ?: Typeface.MONOSPACE; p.textSize = 24f * scaleOf(TEXT) * u
                val items = listOfNotNull(i.alarmText.takeIf { it.isNotEmpty() }?.let { true to it }, if (left > 0) false to timerText(left) else null)
                // one line: if the long-digit fonts make it wider than the screen allows, everything on it shrinks together (stacking would hit the date)
                val full = items.sumOf { (20.0 + 8.0 + p.measureText(it.second) / u) } + 30.0 * (items.size - 1)
                val f = (420.0 / full).coerceAtMost(1.0).toFloat()
                val bw = 20f * f; val gap = 8f * f; val between = 30f * f; val size = 24f * f
                p.textSize = size * scaleOf(TEXT) * u
                val widths = items.map { bw + gap + p.measureText(it.second) / u }
                val axis = 240f + BandGeometry.axisShift(BandGeometry.divider(clockColonX(i.time, u)))
                val y = clockY(mac) + if (mac) 24f else 28f
                var x = axis - (widths.sum() + between * (items.size - 1)) / 2
                items.forEachIndexed { n, it ->
                    if (it.first) bellIcon(c, x + bw / 2, y - 8f * f, 0.8f * f, u) else timerIcon(c, x + bw / 2, y - 8f * f, 0.8f * f, u)
                    text(c, it.second, x + bw + gap, y, size, u)
                    x += widths[n] + between
                }
            }
            badges(c, i, u)
            softKeys(c, if ((model?.homeFocus ?: -1) >= 0) "Select" else i.softLeft, model?.softKeys?.second ?: i.softRight, u) // the model knows whether the right key is Music or Contacts
        }

        private fun menu(c: Canvas, m: LauncherModel, u: Float) {
            if (m.layout == Layout.GRID) { menuGrid(c, m, u); return }
            // title bar
            titleBar(c, m.title, u)
            val rowH = 62f
            val visible = LIST_VISIBLE
            val first = m.listFirst(visible)
            for (n in 0 until visible) {
                val idx = first + n
                val row = m.rows.getOrNull(idx) ?: break
                val top = 104f + n * rowH
                val style = chrome.row(m, idx)
                val prefix = style?.prefix ?: if (m.plainRows > 0 || m.screen == Screen.WHEREDETAIL) "" else if (idx < 9) "${idx + 1}  " else if (idx == 9 && m.screen == Screen.MENU) "0  " else "   "
                val tx = style?.tx ?: 20f
                val room = style?.room ?: 440f
                // a place found by kind has its kind in smaller type under the name (the name moves up a little to make room)
                val sub = m.rowSubs.getOrNull(idx).orEmpty()
                val ny = if (sub.isEmpty()) top + 42f else top + 31f; val ns = if (sub.isEmpty()) 32f else 29f
                val label = cutRow(prefix + row, room, ns)
                if (idx == m.selected) {
                    val sel = chrome.selection(this)
                    fill.color = sel?.first ?: ink; c.drawRect(0f, top * u, width.toFloat(), (top + rowH - 4) * u, fill); fill.color = ink
                    // a playlist's marker (downloaded, downloading, pinned) stays fixed at the left while only the name scrolls
                    val mark = if (m.screen == Screen.PLAYLISTS) listOf(PlaylistOffline.DOWNLOADED, PlaylistOffline.DOWNLOADING, PlaylistOffline.PINNED).firstOrNull { row.startsWith(it) } else null
                    if (mark == null) marquee(c, prefix + row, tx, ny, ns, room, u, bold = true, color = sel?.second ?: lcd)
                    else {
                        val fixed = prefix + mark; val fw = measureBold(fixed, 32f)
                        text(c, fixed, tx, top + 42f, 32f, u, bold = true, color = sel?.second ?: lcd)
                        marquee(c, row.removePrefix(mark), tx + fw, top + 42f, 32f, room - fw, u, bold = true, color = sel?.second ?: lcd)
                    }
                } else text(c, label, tx, ny, ns, u)
                if (sub.isNotEmpty()) text(c, ellipsize(sub, room, 21f), tx, top + 54f, 21f, u, color = if (idx == m.selected) (chrome.selection(this)?.second ?: lcd) else ink)
                chrome.rowExtras(this, c, m, idx, top, idx == m.selected, u)
                // the Music menu shows what each provider is doing: a play triangle while it plays, two bars while it is paused
                m.rowMarks.getOrNull(idx)?.takeIf { it > 0 }?.let { mark ->
                    val cy = top + 27f; val col = if (idx == m.selected) (chrome.selection(this)?.second ?: lcd) else ink
                    p.color = col
                    if (mark == 2) { val t = android.graphics.Path().apply { moveTo(432f * u, (cy - 11) * u); lineTo(454f * u, cy * u); lineTo(432f * u, (cy + 11) * u); close() }; c.drawPath(t, p.also { it.style = Paint.Style.FILL }) }
                    else { c.drawRect(434f * u, (cy - 11) * u, 441f * u, (cy + 11) * u, p.also { it.style = Paint.Style.FILL }); c.drawRect(447f * u, (cy - 11) * u, 454f * u, (cy + 11) * u, p) }
                    p.color = ink
                }
            }
            if (m.rows.size > visible) chrome.scrollBar(this, c, first, m.rows.size, visible, u)
            m.wherePlaceNote.let { if (it.isNotEmpty()) text(c, it, 240f, 566f, 22f, u, align = Paint.Align.CENTER) }
            if (m.rows.isEmpty()) text(c, if (m.screen == Screen.WHATSAPP) "No chats yet" else if (m.screen == Screen.PLAYLISTS || m.screen == Screen.DEVICES || m.screen == Screen.SEARCHRES) m.spStatus.ifEmpty { "Empty" } else if (m.screen == Screen.WHEREPLACES || m.screen == Screen.WHEREJOURNEYS) m.whereStatus.ifEmpty { "Empty" } else if (m.screen == Screen.DATAAPPS) m.dataAppsStatus.ifEmpty { "Empty" } else if (m.screen == Screen.NOTES && !io.github.durkimat.phoneguard.notes.NoteService.connected) "Notifications are not connected" else "Empty", 240f, 300f, 30f, u, align = Paint.Align.CENTER)
            if (m.screen == Screen.PLAYLISTS && m.rows.isNotEmpty()) text(c, m.playlistHint(), 240f, 560f, 24f, u, align = Paint.Align.CENTER) // the keys that pin the highlighted playlist and download it
            if (m.screen == Screen.WHATSAPP && m.waNote.isNotEmpty()) text(c, m.waNote, 240f, 556f, 26f, u, align = Paint.Align.CENTER)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        /** The main menu as a grid of pixel icons: the chosen tile is inverted and its name sits above the soft keys. */
        private fun menuGrid(c: Canvas, m: LauncherModel, u: Float) {
            titleBar(c, m.title, u)
            val first = m.gridFirstRow()
            stroke.strokeWidth = 2 * u
            for (r in 0 until GRID_ROWS) for (col in 0 until GRID_COLS) {
                val idx = (first + r) * GRID_COLS + col
                val item = m.items.getOrNull(idx) ?: continue
                val x = GRID_LEFT + col * (GRID_TILE_W + GRID_GAP)
                val y = GRID_TOP + r * (GRID_TILE_H + GRID_GAP)
                val on = idx == m.selected
                val inset = chrome.gridInset // a window frame sits at the edge, so its tiles step in
                val tile = RectF((x + inset) * u, y * u, (x + GRID_TILE_W - inset) * u, (y + GRID_TILE_H) * u)
                val sel = if (on) chrome.selection(this) else null
                if (on) { fill.color = sel?.first ?: ink; c.drawRect(tile, fill); fill.color = ink } else c.drawRect(tile, stroke)
                val icon = m.iconFor(item)
                val fg = if (on) sel?.second ?: lcd else ink; val bg = if (on) sel?.first ?: ink else lcd
                PixelIcons.draw(c, icon, (x + (GRID_TILE_W - 64f) / 2) * u, (y + (GRID_TILE_H - 64f) / 2) * u, 4f * u, fg, bg, chrome.iconFill(this, icon, on) ?: fg)
            }
            val rows = (m.items.size + GRID_COLS - 1) / GRID_COLS
            if (rows > GRID_ROWS) { // a thin bar on the right edge shows there are more rows to scroll to
                val span = GRID_ROWS * (GRID_TILE_H + GRID_GAP) - GRID_GAP
                val thumb = span * GRID_ROWS / rows
                val top = GRID_TOP + (span - thumb) * first / (rows - GRID_ROWS)
                c.drawRect(474f * u, top * u, 478f * u, (top + thumb) * u, fill)
            }
            m.items.getOrNull(m.selected)?.let { marquee(c, it.label, 20f, 556f, 34f, 440f, u, bold = true, center = true) }
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        /** A row that does not fit: a "label: value" row keeps its whole label and loses the end of the value; anything else is cut at the end. (The highlighted row scrolls instead.) */
        fun cutRow(s: String, room: Float, size: Float): String {
            val i = s.indexOf(": ")
            if (i < 0 || measureBold(s, size) <= room) return ellipsize(s, room, size)
            val head = s.substring(0, i + 2); val headW = measureBold(head, size)
            if (headW > room * 0.7f) return ellipsize(s, room, size)
            return head + ellipsize(s.substring(i + 2), room - headW, size)
        }

        private val BODY = 28f

        private val mp = Paint(Paint.ANTI_ALIAS_FLAG)
        private fun bodyPaint(role: Int = TEXT): Paint {
            mp.typeface = faces[role] ?: Typeface.MONOSPACE
            mp.textSize = BODY * scaleOf(role); mp.letterSpacing = 0f
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
                    val after = Graphemes.next(text, j) // an emoji is measured and kept whole
                    if (paint.measureText(text, i, after) > max) {
                        if (lastSpace > i) { out.add(i until lastSpace); i = lastSpace + 1 }
                        else { val end = if (j > i) j else after; out.add(i until end); i = end }
                        done = true; break
                    }
                    j = after
                }
                if (!done) { out.add(i until text.length); break }
            }
            return out
        }

        /** Splits text into lines that fit the screen width in the current font. Called by the model. */
        fun wrap(text: String): List<String> = spans(text).map { text.substring(it.first, it.last + 1) }

        /** An event or a mail: a head line, then the text scrolled a page at a time. */
        private fun page(c: Canvas, m: LauncherModel, u: Float) {
            titleBar(c, m.title, u)
            text(c, fit(m.pageHead, 448f, 24f), 16f, 134f, 24f, u, bold = true)
            val lines = wrap(m.pageBody)
            lines.drop(m.scroll).take(m.pageLines).forEachIndexed { n, s -> text(c, s, 16f, 176f + n * 36f, BODY, u) }
            if (m.scroll > 0) arrowV(c, 456f, 150f, -1, u)
            if (m.scroll + m.pageLines < lines.size) arrowV(c, 456f, 548f, 1, u)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        /** A month of days with a dot under each that has an event, the chosen day inverted, and that day's events underneath. */
        private fun calMonth(c: Canvas, m: LauncherModel, u: Float) {
            titleBar(c, m.title, u)
            val cells = io.github.durkimat.phoneguard.calendar.Cal.monthCells(m.calDay.year, m.calDay.monthValue)
            val busy = HashSet<java.time.LocalDate>()
            for (e in m.calEvents) {
                var d = maxOf(io.github.durkimat.phoneguard.calendar.Cal.startDate(e, m.zone), cells.first()); val end = minOf(io.github.durkimat.phoneguard.calendar.Cal.endDate(e, m.zone), cells.last())
                while (!d.isAfter(end)) { busy += d; d = d.plusDays(1) }
            }
            val faint = (ink and 0x00FFFFFF) or (0x66 shl 24)
            val cw = 64f; val x0 = 16f; val gy = 138f; val rh = 48f
            listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, s -> text(c, s, x0 + i * cw + cw / 2, 128f, 22f, u, bold = true, align = Paint.Align.CENTER) }
            val sel = chrome.selection(this)
            for (n in 0 until 42) {
                val d = cells[n]; val x = x0 + (n % 7) * cw; val y = gy + (n / 7) * rh
                val isSel = d == m.calDay
                if (isSel) { fill.color = sel?.first ?: ink; c.drawRect((x + 2) * u, y * u, (x + cw - 2) * u, (y + rh - 2) * u, fill); fill.color = ink }
                val tc = if (isSel) (sel?.second ?: lcd) else if (d.monthValue != m.calDay.monthValue) faint else ink
                text(c, "" + d.dayOfMonth, x + cw / 2, y + 30f, 26f, u, bold = d == m.today, align = Paint.Align.CENTER, color = tc)
                if (d in busy) { fill.color = tc; c.drawRect((x + cw / 2 - 4) * u, (y + 36f) * u, (x + cw / 2 + 4) * u, (y + 42f) * u, fill); fill.color = ink }
                if (d == m.today && !isSel) { stroke.strokeWidth = 2 * u; c.drawRect((x + 2) * u, y * u, (x + cw - 2) * u, (y + rh - 2) * u, stroke) }
            }
            val list = io.github.durkimat.phoneguard.calendar.Cal.eventsOn(m.calEvents, m.calDay, m.zone)
            val top = gy + 6 * rh + 24f
            text(c, io.github.durkimat.phoneguard.calendar.Cal.dayLabel(m.calDay, m.today), 16f, top, 24f, u, bold = true)
            if (list.isEmpty()) text(c, "No events", 16f, top + 32f, 24f, u, color = faint)
            list.take(3).forEachIndexed { i, e -> text(c, fit(io.github.durkimat.phoneguard.calendar.Cal.time(e, m.zone) + "  " + e.title.ifBlank { "(no title)" }, 448f, 24f), 16f, top + 32f + i * 30f, 24f, u) }
            if (list.size > 3) text(c, "+" + (list.size - 3) + " more", 464f, top, 22f, u, align = Paint.Align.RIGHT)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private val chunky = Paint().apply { isFilterBitmap = false }

        private fun thread(c: Canvas, m: LauncherModel, u: Float) {
            val convo = m.openConvo ?: return
            val msg = m.shownMsg ?: return
            titleBar(c, m.title, u)
            val lines = m.messageLines(msg)
            val whenText = SimpleDateFormat(if (sameDay(msg.date)) "HH:mm" else "d MMM HH:mm", Locale.UK).format(Date(msg.date))
            val pos = "${m.selected + 1}/${convo.msgs.size}"
            val who = if (msg.mine) "Me" else msg.sender.ifBlank { convo.name }
            val room = 448f - measureBold(pos, 24f) - measureBold("  $whenText", 24f)
            text(c, fit(who, room, 24f) + "  " + whenText, 16f, 134f, 24f, u, bold = true)
            text(c, pos, 464f, 134f, 24f, u, align = Paint.Align.RIGHT)
            val page = lines.drop(m.scroll).take(m.pageLines)
            page.forEachIndexed { n, s -> text(c, s, 16f, 176f + n * 36f, BODY, u) }
            if (m.scroll == 0 && m.pictureOf(msg) != Rich.Picture.NONE) messagePicture(c, m, msg, u)
            if (m.scroll > 0) arrowV(c, 456f, 150f, -1, u)
            if (m.scroll + m.pageLines < lines.size) arrowV(c, 456f, 548f, 1, u)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        /** A message's picture in the first lines of its page: dithered like the gallery, or a framed box saying what it is; what OK does is written along its foot. */
        private fun messagePicture(c: Canvas, m: LauncherModel, msg: Msg, u: Float) {
            val left = 16f; val top = 148f; val w = 448f; val h = 290f
            val ready = m.pictureOf(msg) == Rich.Picture.READY
            val b = if (ready) ChatPictures.peek(msg.mediaPath, lcd, ink) else null
            if (b != null) {
                val s = minOf(w / b.width, h / b.height); val dw = b.width * s; val dh = b.height * s
                c.drawBitmap(b, null, RectF((left + (w - dw) / 2) * u, (top + (h - dh) / 2) * u, (left + (w + dw) / 2) * u, (top + (h + dh) / 2) * u), chunky)
            } else {
                stroke.strokeWidth = 2 * u
                c.drawRect(left * u, top * u, (left + w) * u, (top + h) * u, stroke)
                text(c, if (ready) "Loading..." else Rich.kindName(msg.kind), 240f, top + 130f, 36f, u, bold = true, align = Paint.Align.CENTER)
            }
            val hint = m.pictureHint ?: return
            val band = measureBold(hint, 26f) + 28f
            fill.color = ink
            c.drawRect((240f - band / 2) * u, (top + h - 46f) * u, (240f + band / 2) * u, (top + h - 8f) * u, fill)
            text(c, hint, 240f, top + h - 18f, 26f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
        }

        /** Cuts a long line to fit the screen, ending in "..." (previews of messages are often longer than one line). */
        override fun ellipsize(s: String, room: Float, size: Float): String {
            val paint = bodyPaint(); paint.textSize = size * scaleOf(TEXT); paint.isFakeBoldText = true
            val fits = paint.measureText(s) <= room
            if (fits) { paint.isFakeBoldText = false; return s }
            var t = s
            while (t.length > 1 && paint.measureText("$t...") > room) t = Graphemes.dropLast(t)
            paint.isFakeBoldText = false
            return "$t..."
        }

        override fun measureBold(s: String, size: Float, role: Int): Float {
            val paint = bodyPaint(role); paint.textSize = size * scaleOf(role); paint.isFakeBoldText = true
            val w = paint.measureText(if (role == LABEL && choices[role].caps) s.uppercase() else s); paint.isFakeBoldText = false
            return w
        }

        /** Shortens a name to fit: names lose their end, numbers lose their front so the last digits stay visible. */
        private fun fit(s: String, room: Float, size: Float): String {
            if (measureBold(s, size) <= room) return s
            val number = s.firstOrNull()?.let { it.isDigit() || it == '+' } == true
            var t = s
            while (t.length > 1 && measureBold((if (number) "…$t" else "$t…"), size) > room) t = if (number) t.drop(1) else Graphemes.dropLast(t)
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
                val field = if (m.writeSearch) "Search_" else if (m.writeWifi != null) "Password_" else "To: " + m.writeLabel.ifBlank { m.writeTo } + "_"
                if (chrome.fieldTitle(field)) { /* the chrome shows it in its title bar */ }
                else {
                    c.drawRect(0f, 47 * u, width.toFloat(), 99 * u, fill)
                    text(c, field, 16f, 85f, 30f, u, bold = true, color = lcd)
                }
            } else {
                text(c, m.writeHead ?: if (m.writeSearch) "Search" else if (m.writeWifi != null) "Password: " + m.writeWifi!!.ssid else "To: " + m.writeLabel.ifBlank { m.writeTo }, 16f, 85f, 30f, u, bold = true)
                stroke.strokeWidth = 2 * u; c.drawLine(0f, 99 * u, width.toFloat(), 99 * u, stroke)
            }
            val body = m.writeText
            val lines = spans(body)
            val comp = m.composingRange
            val paint = bodyPaint()
            val cursorLine = if (comp != null || m.writeField == 0) -1 else lines.indexOfFirst { it.first <= m.cursor && m.cursor <= it.last + 1 }.let { if (it < 0) lines.size - 1 else it }
            val shown = 11
            var first = (lines.size - shown).coerceAtLeast(0) // keep the end, where the cursor usually is
            if (cursorLine in 0 until first) first = cursorLine
            for (n in 0 until shown) {
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
            if (m.voiceShown) voiceButton(c, m, u)
            pickButtons(c, m, u)
            if (m.voiceState == 0) text(c, m.modeName, 16f, 560f, 24f, u, bold = true)
            if (m.writeWa) text(c, "WhatsApp", 240f, 560f, 24f, u, align = Paint.Align.CENTER)
            text(c, "" + m.writeText.length, 464f, 560f, 24f, u, align = Paint.Align.RIGHT)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        /** A small plain microphone in the footer, left of the character counter; a thin outline shows it has the focus. Also shows the state of a recording. */
        private fun voiceButton(c: Canvas, m: LauncherModel, u: Float) {
            val focused = m.writeButton == WriteButton.MIC || m.voiceState != 0
            val cx = buttonX(m, WriteButton.MIC)
            if (focused) { stroke.strokeWidth = 1.5f * u; stroke.color = ink; c.drawRect((cx - 15) * u, 534 * u, (cx + 15) * u, 566 * u, stroke) }
            fill.color = ink; stroke.color = ink; stroke.strokeWidth = 2 * u
            c.drawRoundRect(android.graphics.RectF((cx - 4) * u, 538 * u, (cx + 4) * u, 553 * u), 4 * u, 4 * u, fill)
            c.drawArc(android.graphics.RectF((cx - 8) * u, 545 * u, (cx + 8) * u, 559 * u), 0f, 180f, false, stroke)
            c.drawLine(cx * u, 559 * u, cx * u, 563 * u, stroke)
            when (m.voiceState) {
                1 -> { // listening: level bars and what to do
                    text(c, "Listening", 16f, 560f, 24f, u, bold = true)
                    for (b in 0 until 4) { val h = 8f + b * 6f; if (b < m.voiceLevel) c.drawRect((150 + b * 12) * u, (556 - h) * u, (158 + b * 12) * u, 556 * u, fill) else { stroke.strokeWidth = 1.5f * u; c.drawRect((150 + b * 12) * u, (556 - h) * u, (158 + b * 12) * u, 556 * u, stroke) } }
                    text(c, "let go to send", 240f, 530f, 24f, u, align = Paint.Align.CENTER)
                }
                2 -> text(c, "Sending...", 16f, 560f, 24f, u, bold = true)
            }
            if (m.voiceState == 0 && m.writeButton == WriteButton.MIC) text(c, "hold OK and speak", 240f, 530f, 24f, u, align = Paint.Align.CENTER)
        }

        /** Where each button under the text sits: they end just left of the character count, in the order of [LauncherModel.writeButtons]. */
        private fun buttonX(m: LauncherModel, b: WriteButton): Float {
            val shown = m.writeButtons
            val i = shown.indexOf(b)
            return if (i < 0) 396f else 396f - (shown.size - 1 - i) * 38f
        }

        /** The emoji button (a little face) and the sticker button (a square with a turned-up corner), framed when chosen. */
        private fun pickButtons(c: Canvas, m: LauncherModel, u: Float) {
            if (m.voiceState != 0) return
            for (b in m.writeButtons) {
                if (b == WriteButton.MIC) continue
                val cx = buttonX(m, b)
                if (m.writeButton == b) { stroke.strokeWidth = 1.5f * u; stroke.color = ink; c.drawRect((cx - 16) * u, 534 * u, (cx + 16) * u, 566 * u, stroke) }
                stroke.color = ink; stroke.strokeWidth = 2 * u; fill.color = ink
                if (b == WriteButton.EMOJI) {
                    c.drawCircle(cx * u, 550 * u, 11 * u, stroke)
                    c.drawCircle((cx - 4) * u, 547 * u, 1.8f * u, fill); c.drawCircle((cx + 4) * u, 547 * u, 1.8f * u, fill)
                    c.drawArc(android.graphics.RectF((cx - 6) * u, 545 * u, (cx + 6) * u, 557 * u), 20f, 140f, false, stroke)
                } else {
                    val p = android.graphics.Path().apply {
                        moveTo((cx - 10) * u, 540 * u); lineTo((cx + 10) * u, 540 * u); lineTo((cx + 10) * u, 552 * u); lineTo((cx + 2) * u, 560 * u); lineTo((cx - 10) * u, 560 * u); close()
                        moveTo((cx + 10) * u, 552 * u); lineTo((cx + 2) * u, 552 * u); lineTo((cx + 2) * u, 560 * u)
                    }
                    c.drawPath(p, stroke)
                }
            }
        }

        /** The route drawn on a plain background (no map tiles): walking dotted, vehicles solid, the leg in focus thick, a dot at the start, a square at the end. */
        private fun whereMap(c: Canvas, m: LauncherModel, u: Float) {
            titleBar(c, m.title, u)
            val segs = m.whereMapSegments
            val dot = m.whereDot
            val fit0 = m.whereMapFit
            val x0 = 12f; val y0 = 108f; val bw = io.github.durkimat.phoneguard.where.MapPicture.BOX_W; val bh = io.github.durkimat.phoneguard.where.MapPicture.BOX_H
            stroke.strokeWidth = 1.5f * u; stroke.color = ink; c.drawRect(x0 * u, y0 * u, (x0 + bw) * u, (y0 + bh) * u, stroke)
            if (fit0 == null) text(c, "No map for this one", 240f, 300f, 28f, u, align = Paint.Align.CENTER)
            else {
                val fit = fit0
                // the map pictures under everything else, in light tones of the ink; whatever has not arrived stays plain
                var pictures = 0
                c.save(); c.clipRect(x0 * u, y0 * u, (x0 + bw) * u, (y0 + bh) * u)
                tilePaint.color = ink
                for (t in m.whereMapTiles) io.github.durkimat.phoneguard.where.TileMap.get(t.key)?.let { bmp ->
                    pictures++
                    c.drawBitmap(bmp, null, android.graphics.RectF((x0 + t.left) * u, (y0 + t.top) * u, (x0 + t.left + t.size) * u + 1f, (y0 + t.top + t.size) * u + 1f), tilePaint)
                }
                c.restore()
                for (pass in 0..1) for (s in segs) {
                    if (s.current != (pass == 1)) continue // the leg in focus is drawn last, on top
                    val path = android.graphics.Path()
                    s.points.forEachIndexed { i, p -> val (x, y) = fit.xy(p.first, p.second); if (i == 0) path.moveTo((x0 + x) * u, (y0 + y) * u) else path.lineTo((x0 + x) * u, (y0 + y) * u) }
                    val w = (if (s.current) 6f else 3f) * u; stroke.strokeWidth = w; stroke.pathEffect = if (s.walk) android.graphics.DashPathEffect(floatArrayOf(3f * w, 2.5f * w), 0f) else null
                    c.drawPath(path, stroke); stroke.pathEffect = null
                }
                val route = io.github.durkimat.phoneguard.where.MapPicture.allPoints(segs)
                val a = fit.xy(route.first().first, route.first().second); val b = fit.xy(route.last().first, route.last().second)
                fill.color = ink; stroke.strokeWidth = 2.5f * u
                c.drawCircle((x0 + a.first) * u, (y0 + a.second) * u, 8f * u, stroke)     // the start: a ring
                c.drawRect((x0 + b.first - 9) * u, (y0 + b.second - 9) * u, (x0 + b.first + 9) * u, (y0 + b.second + 9) * u, fill) // the end: a square
                if (dot != null) { // the child: a dot with a cone showing which way the phone faces
                    val d = fit.xy(dot.first, dot.second); val cx = (x0 + d.first) * u; val cy = (y0 + d.second) * u
                    m.whereHeading?.let { h ->
                        val r = Math.toRadians(h.toDouble())
                        fun pt(deg: Double, len: Float) = floatArrayOf(cx + (Math.sin(r + Math.toRadians(deg)) * len * u).toFloat(), cy - (Math.cos(r + Math.toRadians(deg)) * len * u).toFloat())
                        val tip = pt(0.0, 34f); val l = pt(-24.0, 15f); val rr = pt(24.0, 15f)
                        val cone = android.graphics.Path().apply { moveTo(cx, cy); lineTo(l[0], l[1]); lineTo(tip[0], tip[1]); lineTo(rr[0], rr[1]); close() }
                        tilePaint.style = Paint.Style.FILL; tilePaint.color = (ink and 0x00FFFFFF) or (0x70 shl 24); c.drawPath(cone, tilePaint) // see-through, so the map shows under it
                        stroke.strokeWidth = 1.5f * u; c.drawPath(cone, stroke)
                    }
                    c.drawCircle(cx, cy, 7f * u, fill); stroke.strokeWidth = 2f * u; c.drawCircle(cx, cy, 11f * u, stroke)
                }
                m.whereMapTarget?.let { t -> // the next stop or the destination is off the picture: an arrow on the edge points to it
                    val (tx, ty) = fit.xy(t.first, t.second)
                    io.github.durkimat.phoneguard.where.MapPicture.edgeArrow(tx, ty, bw, bh, 16f)?.let { (ax, ay, ang) ->
                        fun q(len: Float, side: Double, back: Float) = floatArrayOf((x0 + ax) * u + (Math.sin(ang) * len + Math.sin(ang + side) * back).toFloat() * u, (y0 + ay) * u - (Math.cos(ang) * len + Math.cos(ang + side) * back).toFloat() * u)
                        val tip = q(8f, 0.0, 0f); val l = q(-8f, -Math.PI / 2, 9f); val r = q(-8f, Math.PI / 2, 9f)
                        fill.color = lcd; stroke.strokeWidth = 5f * u; val path = android.graphics.Path().apply { moveTo(tip[0], tip[1]); lineTo(l[0], l[1]); lineTo(r[0], r[1]); close() }
                        c.drawPath(path, stroke.also { it.color = lcd }); stroke.color = ink; fill.color = ink; c.drawPath(path, fill) // an outline in paper colour keeps it clear of the map
                    }
                }
                fun halo(l: Float, t: Float, r: Float, b: Float) { fill.color = lcd; c.drawRect((x0 + l) * u, (y0 + t) * u, (x0 + r) * u, (y0 + b) * u, fill); fill.color = ink }
                halo(bw - 38f, 4f, bw - 2f, 36f); text(c, "N", x0 + bw - 20f, y0 + 26f, 22f, u, bold = true, align = Paint.Align.CENTER) // north is up
                if (dot == null) { halo(2f, 4f, 150f, 36f); text(c, "No GPS yet", x0 + 8f, y0 + 26f, 22f, u) }
                val credit = if (pictures > 0) "(c) OpenStreetMap contributors" else if (io.github.durkimat.phoneguard.where.TileMap.problem == 429) "Map pictures used up for today" else ""
                if (io.github.durkimat.phoneguard.where.TileMap.capped) { halo(2f, bh - 46f, bw - 60f, bh - 24f); text(c, "Map pictures limit for this visit", x0 + 8f, y0 + bh - 30f, 15f, u) }
                run { // the zoom hint, bottom right: a rounded box with "3", a solid triangle pointing down, "9" (3 zooms in, 9 out; 6, not drawn, shows it all)
                    val bx = x0 + bw - 12f - 42f; val by = y0 + bh - 12f - 122f
                    fill.color = lcd; c.drawRoundRect(bx * u, by * u, (bx + 42f) * u, (by + 122f) * u, 14f * u, 14f * u, fill)
                    stroke.strokeWidth = 3f * u; stroke.color = ink; c.drawRoundRect((bx + 1.5f) * u, (by + 1.5f) * u, (bx + 40.5f) * u, (by + 120.5f) * u, 13f * u, 13f * u, stroke)
                    text(c, "3", bx + 21f, by + 31f, 24f, u, bold = true, align = Paint.Align.CENTER)
                    fill.color = ink
                    c.drawPath(android.graphics.Path().apply { moveTo((bx + 4f) * u, (by + 33f) * u); lineTo((bx + 38f) * u, (by + 33f) * u); lineTo((bx + 21f) * u, (by + 89f) * u); close() }, fill)
                    text(c, "9", bx + 21f, by + 113f, 24f, u, bold = true, align = Paint.Align.CENTER)
                }
                if (credit.isNotEmpty()) { halo(2f, bh - 24f, bw - 60f, bh - 2f); text(c, credit, x0 + 8f, y0 + bh - 8f, 15f, u) }
            }
            m.whereMapCaption.let { if (it.isNotEmpty()) marquee(c, it, 16f, 536f, 28f, 448f, u, bold = true) }
            text(c, "" + (m.whereMapLeg + 1) + "/" + m.whereLegs.size, 464f, 566f, 22f, u, align = Paint.Align.RIGHT)
            text(c, "< leg >", 240f, 566f, 22f, u, align = Paint.Align.CENTER) // Left and Right step through the legs
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun calc(c: Canvas, m: LauncherModel, u: Float) {
            titleBar(c, m.title, u)
            text(c, m.calc.pending, 464f, 160f, 30f, u, align = Paint.Align.RIGHT)
            val big = m.calc.display
            var size = 84f
            val paint = bodyPaint()
            paint.textSize = size
            while (paint.measureText(big) > 440f && size > 30f) { size -= 4f; paint.textSize = size }
            text(c, big, 464f, 270f, size / scaleOf(TEXT), u, bold = true, align = Paint.Align.RIGHT)
            stroke.strokeWidth = 2 * u; c.drawLine(16 * u, 296 * u, 464 * u, 296 * u, stroke)
            text(c, "*   + - x /", 16f, 360f, 26f, u)
            text(c, "#   decimal point", 16f, 400f, 26f, u)
            text(c, "Up/Down   + and -", 16f, 440f, 26f, u)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun snake(c: Canvas, m: LauncherModel, u: Float) {
            val g = m.snake
            titleBar(c, m.title, u)
            val cell = 20f; val left = (480f - g.cols * cell) / 2; val top = 110f
            stroke.strokeWidth = 2 * u
            c.drawRect((left - 3) * u, (top - 3) * u, (left + g.cols * cell + 3) * u, (top + g.rows * cell + 3) * u, stroke)
            for ((x, y) in g.body) c.drawRect((left + x * cell + 1) * u, (top + y * cell + 1) * u, (left + x * cell + cell - 1) * u, (top + y * cell + cell - 1) * u, fill)
            // the food is a small hollow square so it is not mistaken for the snake
            c.drawRect((left + g.food.first * cell + 5) * u, (top + g.food.second * cell + 5) * u, (left + g.food.first * cell + cell - 5) * u, (top + g.food.second * cell + cell - 5) * u, stroke)
            val msg = when (g.state) { Snake.State.READY -> "Press a key"; Snake.State.PAUSED -> "Paused"; Snake.State.OVER -> "Game over"; else -> null }
            if (msg != null) {
                val fg = callout(c, left + 40, top + 150, left + g.cols * cell - 40, top + 250, u)
                text(c, msg, 240f, top + 212f, 34f, u, bold = true, align = Paint.Align.CENTER, color = fg)
            }
            text(c, "Score ${g.score}", 16f, 556f, 26f, u, bold = true)
            text(c, "Best ${m.snakeBest}", 464f, 556f, 26f, u, align = Paint.Align.RIGHT)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun gameHelp(c: Canvas, m: LauncherModel, u: Float) {
            gameTitle(c, m, u)
            text(c, "How to play", 240f, 134f, 26f, u, bold = true, align = Paint.Align.CENTER)
            m.helpLines.forEachIndexed { n, line -> text(c, line, 20f, 176f + n * 34f, 25f, u) }
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun gameTitle(c: Canvas, m: LauncherModel, u: Float) {
            titleBar(c, m.title, u)
        }

        /** A filled box with a message in the theme's light colour. */
        /** A filled box for a message: the chrome may draw its own kind (and then the text is in the ink colour). Returns the text colour. */
        private fun callout(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, u: Float): Int {
            if (chrome.callout(this, c, x0, y0, x1, y1, u)) return ink
            c.drawRect(x0 * u, y0 * u, x1 * u, y1 * u, fill); return lcd
        }

        private fun banner(c: Canvas, msg: String, u: Float, y: Float) {
            val fg = callout(c, 60f, y, 420f, y + 90, u)
            text(c, msg, 240f, y + 58f, 34f, u, bold = true, align = Paint.Align.CENTER, color = fg)
        }

        private fun pairs(c: Canvas, m: LauncherModel, u: Float) {
            val g = m.pairs
            gameTitle(c, m, u)
            val size = 100f; val left = 40f; val top = 112f
            for (i in 0 until Pairs.COLS * Pairs.ROWS) {
                val x = left + (i % Pairs.COLS) * size; val y = top + (i / Pairs.COLS) * size
                val r = RectF((x + 4) * u, (y + 4) * u, (x + size - 4) * u, (y + size - 4) * u)
                val up = g.shown[i] != 0
                stroke.strokeWidth = (if (i == g.cursor) 6 else 2) * u
                if (g.shown[i] == 2) { c.drawRect(r, stroke); text(c, Pairs.SYMBOLS[g.cards[i]].toString(), x + size / 2, y + 68f, 44f, u, align = Paint.Align.CENTER) }
                else if (up) { c.drawRect(r, fill); text(c, Pairs.SYMBOLS[g.cards[i]].toString(), x + size / 2, y + 68f, 48f, u, bold = true, align = Paint.Align.CENTER, color = lcd) }
                else { c.drawRect(r, stroke); text(c, "?", x + size / 2, y + 66f, 40f, u, align = Paint.Align.CENTER) }
            }
            stroke.strokeWidth = 2 * u
            if (g.won) banner(c, "Done in ${g.moves}", u, 230f)
            text(c, "Moves ${g.moves}", 16f, 556f, 26f, u, bold = true)
            if (m.pairsBest > 0) text(c, "Best ${m.pairsBest}", 464f, 556f, 26f, u, align = Paint.Align.RIGHT)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun shooter(c: Canvas, m: LauncherModel, u: Float) {
            val g = m.shooter
            gameTitle(c, m, u)
            val cell = 10f; val left = (480f - g.cols * cell) / 2; val top = 108f
            stroke.strokeWidth = 2 * u
            c.drawRect((left - 3) * u, (top - 3) * u, (left + g.cols * cell + 3) * u, (top + g.rows * cell + 3) * u, stroke)
            fun r(x: Int, y: Int, w: Int, h: Int) = RectF((left + x * cell) * u, (top + y * cell) * u, (left + (x + w) * cell) * u, (top + (y + h) * cell) * u)
            fun clip(x: Int, w: Int) = x >= 0 && x + w <= g.cols
            for (s in g.stars) c.drawRect(r(s[0], s[1], 1, 1).also { it.right = it.left + 2 * u; it.bottom = it.top + 2 * u }, fill)
            for (cr in g.crates) if (clip(cr[0], 3)) { c.drawRect(r(cr[0], cr[1], 3, 3), stroke); text(c, "PSB+"[cr[2]].toString(), left + (cr[0] + 1.5f) * cell, top + (cr[1] + 2.6f) * cell, 20f, u, bold = true, align = Paint.Align.CENTER) }
            for (e in g.enemies) if (clip(e.x, e.w)) {
                if (e.kind == 2) {
                    c.drawRect(r(e.x, e.y, e.w, e.h), fill); c.drawRect(r(e.x + 2, e.y + 3, e.w - 4, e.h - 6), stroke.also { it.color = lcd }); stroke.color = ink
                    c.drawRect(r(e.x + 4, e.y + e.h / 2 - 1, 4, 2), stroke.also { it.color = lcd }); stroke.color = ink
                    c.drawRect(r(e.x, e.y - 2, (e.w * e.hp / (28 + 12 * g.stage).coerceAtLeast(1)).coerceIn(0, e.w), 1), fill)
                }
                else if (e.tank) { c.drawRect(r(e.x, e.y, e.w, e.h), fill); c.drawRect(r(e.x + 2, e.y + 2, e.w - 4, e.h - 4), stroke.also { it.color = lcd }); stroke.color = ink }
                else { c.drawRect(r(e.x, e.y, e.w, e.h), stroke); c.drawRect(r(e.x + 1, e.y + 1, 2, 1), fill) }
            }
            for (s in g.shots) if (clip(s[0], 2)) c.drawRect(r(s[0], s[1], 2, 1), fill)
            for (s in g.enemyShots) if (clip(s[0], 2)) c.drawRect(r(s[0], s[1], 2, 1), stroke)
            // the beam, and the charge gauge as a small bar under the ship
            if (g.beam > 0) c.drawRect(r(g.shipX + g.shipW, g.shipY + 1, g.cols - g.shipX - g.shipW, 2), fill)
            if (g.charge > 3) {
                val frac = (g.charge.toFloat() / g.chargeNeeded).coerceAtMost(1f)
                c.drawRect(r(g.shipX, (g.shipY + g.shipH + 1).coerceAtMost(g.rows - 1), (g.shipW * frac).toInt().coerceAtLeast(1), 1), fill)
            }
            if (g.hasPod && clip(g.podX, g.podW)) { c.drawRect(r(g.podX, g.podY, g.podW, g.shipH), fill); c.drawRect(r(g.podX + 1, g.podY + 1, 1, g.shipH - 2), stroke.also { it.color = lcd }); stroke.color = ink }
            if (g.invulnerable % 2 == 0) {
                val rr = r(g.shipX, g.shipY, g.shipW, g.shipH)
                val path = android.graphics.Path().apply { moveTo(rr.left, rr.top); lineTo(rr.right, (rr.top + rr.bottom) / 2); lineTo(rr.left, rr.bottom); lineTo(rr.left + 2 * cell, (rr.top + rr.bottom) / 2); close() }
                c.drawPath(path, fill)
            }
            val msg = when (g.state) { Shooter.State.READY -> "Press OK"; Shooter.State.PAUSED -> "Paused"; Shooter.State.OVER -> "Game over"; else -> null }
            if (msg != null) banner(c, msg, u, 230f)
            if (g.banner > 0) banner(c, "Stage ${g.stage}", u, 230f)
            if (g.state == Shooter.State.READY) { text(c, "Hold OK: beam   #: pod out/in", 240f, 340f, 22f, u, align = Paint.Align.CENTER) }
            text(c, "Stage ${g.stage}  ${g.weapon.label}  Pod ${when (g.podMode) { Shooter.PodMode.OFF -> "-"; Shooter.PodMode.ATTACHED -> "on"; else -> "out" }}", 16f, 530f, 22f, u)
            text(c, "Score ${g.score}  Lives ${g.lives}", 16f, 556f, 26f, u, bold = true)
            text(c, "Best ${m.shooterBest}", 464f, 556f, 26f, u, align = Paint.Align.RIGHT)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun seeds(c: Canvas, m: LauncherModel, u: Float) {
            val g = m.seeds
            gameTitle(c, m, u)
            fun box(x: Float, y: Float, w: Float, h: Float, n: Int, on: Boolean = false) {
                val r = RectF(x * u, y * u, (x + w) * u, (y + h) * u)
                if (on) { c.drawRect(r, fill); text(c, "" + n, x + w / 2, y + h / 2 + 14f, 40f, u, bold = true, align = Paint.Align.CENTER, color = lcd) }
                else { stroke.strokeWidth = 2 * u; c.drawRect(r, stroke); text(c, "" + n, x + w / 2, y + h / 2 + 14f, 40f, u, align = Paint.Align.CENTER) }
            }
            text(c, "Phone", 240f, 138f, 26f, u, align = Paint.Align.CENTER)
            box(8f, 150f, 52f, 260f, g.theirs)
            box(428f, 150f, 52f - 8f, 260f, g.yours)
            for (i in 0 until 6) {
                box(64f + i * 60f, 150f, 56f, 120f, g.board[12 - i])
                box(64f + i * 60f, 290f, 56f, 120f, g.board[i], on = i == g.cursor && g.yourGo && !g.over)
            }
            text(c, "You", 240f, 446f, 26f, u, align = Paint.Align.CENTER)
            text(c, g.note, 240f, 520f, 30f, u, bold = true, align = Paint.Align.CENTER)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun dial(c: Canvas, m: LauncherModel, u: Float) {
            val n = m.dialNumber
            if (n.isEmpty()) text(c, "Enter a number", 240f, 300f, 34f, u, align = Paint.Align.CENTER)
            var size = 84f
            val paint = bodyPaint(); paint.textSize = size
            while (paint.measureText(n) > 440f && size > 30f) { size -= 4f; paint.textSize = size }
            text(c, n, 464f, 300f, size / scaleOf(TEXT), u, bold = true, align = Paint.Align.RIGHT)
            m.dialName?.let { text(c, it, 464f, 350f, 30f, u, align = Paint.Align.RIGHT) }
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun callScreen(c: Canvas, m: LauncherModel, u: Float) {
            val call = m.call ?: return
            val who = call.name ?: call.number.ifBlank { "Unknown" }
            val scale = scaleOf(TEXT)
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
                    if (call.phase == CallPhase.RINGING) text(c, "* silences the ring", 240f, 410f, 26f, u, align = Paint.Align.CENTER)
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
                    val fg = callout(c, 60f, 230f, 420f, 370f, u)
                    text(c, "Call ended", 240f, 292f, 36f, u, bold = true, align = Paint.Align.CENTER, color = fg)
                    text(c, clock(call.seconds), 240f, 342f, 36f, u, bold = true, align = Paint.Align.CENTER, color = fg)
                }
            }
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun clock(secs: Int): String = if (secs >= 3600) "%d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60) else "%02d:%02d".format(secs / 60, secs % 60)

        private fun waPair(c: Canvas, m: LauncherModel, u: Float) {
            titleBar(c, m.title, u)
            when {
                m.waLinked -> {
                    text(c, "Linked", 240f, 220f, 52f, u, bold = true, align = Paint.Align.CENTER)
                    text(c, "WhatsApp is ready.", 240f, 280f, 30f, u, align = Paint.Align.CENTER)
                }
                m.waCode != null -> {
                    val lines = wrap("On your own phone open WhatsApp, then Linked devices, Link a device, Link with phone number, and type:")
                    lines.take(7).forEachIndexed { n, s -> text(c, s, 16f, 140f + n * 34f, 26f, u) }
                    text(c, m.waCode ?: "", 240f, 440f, 56f, u, bold = true, align = Paint.Align.CENTER)
                }
                else -> {
                    text(c, "Your WhatsApp number", 240f, 150f, 28f, u, align = Paint.Align.CENTER)
                    text(c, "with country code, no plus", 240f, 186f, 24f, u, align = Paint.Align.CENTER)
                    var size = 52f; val paint = bodyPaint(); paint.textSize = size
                    while (paint.measureText(m.waPairNumber) > 440f && size > 28f) { size -= 4f; paint.textSize = size }
                    text(c, m.waPairNumber + "_", 240f, 300f, size / scaleOf(TEXT), u, bold = true, align = Paint.Align.CENTER)
                    text(c, "e.g. 447700900123", 240f, 360f, 24f, u, align = Paint.Align.CENTER)
                }
            }
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun notice(c: Canvas, s: String, u: Float) {
            if (chrome.notice(this, c, s, u)) return
            // a long notice wraps at word breaks (about 20 characters a line) instead of running off the screen
            val lines = ArrayList<String>(); var cur = ""
            for (w in s.split(' ')) { val t = if (cur.isEmpty()) w else "$cur $w"; if (cur.isNotEmpty() && t.length > 20) { lines.add(cur); cur = w } else cur = t }
            if (cur.isNotEmpty()) lines.add(cur)
            val h = 36f + 44f * lines.size
            c.drawRect(30 * u, (300f - h / 2) * u, 450 * u, (300f + h / 2) * u, fill)
            lines.forEachIndexed { k, l -> text(c, l, 240f, 300f - h / 2 + 42f + 44f * k, 32f, u, bold = true, align = Paint.Align.CENTER, color = lcd) }
        }

        private fun plural(n: Int, what: String) = "$n $what" + if (n == 1) "" else "s"
    }
}

/** The skins. They share the painted screens and differ only in their chrome. */
class NokiaSkin : PaintedSkin(NokiaChrome, "nokia-list")

class MacSkin : PaintedSkin(MacChrome(), "classic-mac")

/** One line of the Now strip: its text, size and weight. */
private class NowLine(val s: String, val size: Float, val bold: Boolean, val fixed: String = "")
