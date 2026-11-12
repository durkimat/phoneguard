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
        private var sizeScale = 1f
        private var downY = 0f
        private var downX = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var dragged = false

        private fun load(id: String): Typeface = try {
            when (id) {
                "sans" -> Typeface.SANS_SERIF
                "vt323" -> Typeface.createFromAsset(context.assets, "fonts/VT323-Regular.ttf")
                "pixelify" -> Typeface.createFromAsset(context.assets, "fonts/PixelifySans.ttf")
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
                        val inBadgeRow = model?.screen == Screen.HOME && downY in 455f..570f && (model?.badgeOverflow() ?: 0f) > 0f
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

        override fun onDraw(c: Canvas) {
            val m = model ?: return
            val i = info ?: return
            val u = width / 480f
            val t = Themes.byId(m.theme)
            lcd = t.lcd; ink = t.ink; accent = m.accent ?: t.accent; fill.color = ink; stroke.color = ink
            val f = if (t.font != null && m.font == Fonts.DEFAULT) t.font else Fonts.byId(m.font).id
            p.typeface = typefaces.getOrPut(f) { load(f) }
            sizeScale = if (f == "vt323") 1.3f else 1f
            c.drawColor(lcd)
            chrome.background(this, c, t, m, u)
            statusLine(c, i, u)
            chrome.beginContent(this, c, u)
            when (m.screen) {
                Screen.HOME -> { home(c, i, u); m.weather?.let { w -> weatherStrip(c, w, u) } }
                Screen.WEATHER -> weatherScreen(c, m, u)
                Screen.BATTERY -> batteryScreen(c, m, u)
                Screen.NOTE -> noteScreen(c, m, u)
                Screen.MUSIC -> musicScreen(c, m, u)
                Screen.THREAD -> thread(c, m, u)
                Screen.WRITE -> write(c, m, u)
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
            m.notice?.let { notice(c, it, u) }
            chrome.endContent(this, c, m, u)
        }

        override fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, u: Float, bold: Boolean, align: Paint.Align, color: Int) {
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
            stroke.strokeWidth = 2 * u
            c.drawLine(0f, 46 * u, width.toFloat(), 46 * u, stroke)
        }

        /** The diagonal that marks a switched-off radio, in full ink over its faint symbol. */
        private fun slash(c: Canvas, cx: Float, u: Float) {
            stroke.color = ink; stroke.strokeWidth = 3f * u
            c.drawLine((cx - 15) * u, 37f * u, (cx + 15) * u, 8f * u, stroke)
        }

        // The two labels are the D-pad's Left and Right: a small arrow shows which way to press.
        private fun softKeys(c: Canvas, left: String, right: String, u: Float) {
            if (chrome.softKeys(this, c, left, right, u)) return
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

        /** New texts, new WhatsApp messages and missed calls as small outline symbols with a count, centred in a row. */
        private fun badges(c: Canvas, i: HomeInfo, u: Float) {
            val m = model ?: return
            val over = m.badgeOverflow()
            if (over > 0f) {
                // too many symbols for the width: they drift gently left and back, pausing at each end, unless a finger has just moved them
                val now = System.currentTimeMillis()
                if (now - m.badgeDragAt > 5000L) {
                    val speed = 30f; val rest = 1500L; val travelMs = (over / speed * 1000).toLong()
                    val t = now % (2 * (travelMs + rest))
                    m.badgeOffset = when { t < rest -> 0f; t < rest + travelMs -> (t - rest) / 1000f * speed; t < 2 * rest + travelMs -> over; else -> over - (t - 2 * rest - travelMs) / 1000f * speed }.coerceIn(0f, over)
                }
                postInvalidateDelayed(60)
                c.save(); c.clipRect(0f, 455f * u, width.toFloat(), 570f * u)
            }
            i.badges.forEachIndexed { n, b ->
                val cx = m.badgeX(n, i.badges.size)
                if (b.kind == BadgeKind.MUSIC) { badgeIcon(c, b.kind, cx, 512f, u); return@forEachIndexed } // a symbol only, no count
                val label = if (b.count > 99) "99+" else b.count.toString()
                p.textSize = 36f * sizeScale * u; p.isFakeBoldText = true
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

        /** The weather row on the home screen: symbol, temperature and a note when rain is on its way. */
        private fun weatherStrip(c: Canvas, w: WeatherData, u: Float) {
            // centred: symbol and temperature as one group, the rain note under it
            val temp = "${w.temp}°"
            p.textSize = 46f * sizeScale * u; p.isFakeBoldText = true
            val tw = p.measureText(temp) / u
            val left = 240f - (62f + 14f + tw) / 2
            weatherIcon(c, w.icon, left + 31f, 392f, 62f, u)
            text(c, temp, left + 76f, 410f, 46f, u, bold = true)
            w.hint()?.let { text(c, it, 240f, 440f, 24f, u, align = Paint.Align.CENTER) }
        }

        private fun musicScreen(c: Canvas, m: LauncherModel, u: Float) {
            titleBar(c, m.title, u)
            val mu = m.music.copy(active = true, playing = true, thisPhone = true, title = "A Long Song Title That Scrolls Along", artist = "Some Artist", album = "The Album", positionMs = 83000, durationMs = 240000, device = "Phone").copy(active = true, playing = true, thisPhone = true, title = "A Long Song Title That Scrolls Along", artist = "Some Artist", album = "The Album", positionMs = 83000, durationMs = 240000, device = "Phone")
            val radio = m.provider == Provider.RADIO
            if (!mu.active) {
                text(c, "Nothing playing", 240f, 230f, 32f, u, bold = true, align = Paint.Align.CENTER)
                val other = m.playingProvider?.takeIf { it != m.provider }
                text(c, if (other != null) (if (other == Provider.RADIO) "Radio" else "Spotify") + " is playing"
                    else if (radio) (if (m.radioNames.isEmpty()) "No stations in this mode" else "OK picks a station")
                    else if (m.spSignedIn) "OK picks a playlist" else "Menu: sign in to Spotify", 240f, 280f, 26f, u, align = Paint.Align.CENTER)
                if (other != null) text(c, if (radio) "OK picks a station" else if (m.spSignedIn) "OK picks a playlist" else "", 240f, 316f, 24f, u, align = Paint.Align.CENTER)
            } else if (chrome.nowPlaying(this, c, m, u)) {
                // the chrome drew the whole player
            } else {
                if (radio) text(c, "Radio" + (if (m.radioIndex >= 0) " " + (m.radioIndex + 1) else ""), 20f, 134f, 24f, u)
                else if (mu.device.isNotEmpty()) text(c, if (mu.thisPhone) "Playing here" else ellipsize("on " + mu.device, 440f, 24f), 20f, 134f, 24f, u)
                marquee(c, mu.title.ifEmpty { "(no title)" }, 20f, 190f, 40f, 440f, u, bold = true)
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
                    text(c, if (m.radioMode == "any") "1-9 presets, hold to set" else "1-9 presets", 240f, 514f, 24f, u, align = Paint.Align.CENTER)
                    text(c, "* bars  0 sleep  # source", 240f, 548f, 24f, u, align = Paint.Align.CENTER)
                    if (m.sleepLabel.isNotEmpty()) text(c, "sleep " + m.sleepLabel, 460f, 134f, 24f, u, align = Paint.Align.RIGHT)
                } else {
                    if (m.spSignedIn) text(c, "* shuffle " + (if (mu.shuffle) "on" else "off") + "   1 repeat " + (when (mu.repeat) { "context" -> "all"; "track" -> "one"; else -> "off" }), 240f, 514f, 24f, u, align = Paint.Align.CENTER)
                    text(c, "7 bars  0 sleep  # source", 240f, 548f, 24f, u, align = Paint.Align.CENTER)
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
                    if (b < lit) c.drawRect(x * u, y * u, (x + barW) * u, (y + blockH) * u, fill)
                    else stroke.also { it.strokeWidth = 1 * u }.let { if (b == 0) c.drawRect(x * u, y * u, (x + barW) * u, (y + blockH) * u, it) }
                }
                val pk = (sp.peak[i] * blocks).toInt().coerceIn(0, blocks - 1)
                if (pk >= lit && sp.peak[i] > 0.05f) { val y = bottom - (pk + 1) * pitch; fill.color = accent; c.drawRect(x * u, y * u, (x + barW) * u, (y + blockH) * u, fill); fill.color = ink }
            }
            if (live) postInvalidateDelayed(70)
        }

        /**
         * A line that fits is drawn plainly. A longer one rests at its start, then scrolls left as a loop: a gap follows the end
         * and the start comes back in behind it, so when the scroll stops the line is exactly where it began (no jump).
         */
        override fun marquee(c: Canvas, s0: String, x: Float, y: Float, size: Float, room: Float, u: Float, bold: Boolean, color: Int, center: Boolean) {
            var s = s0
            if (measureBold(s, size) > room * 3) s = ellipsize(s, room * 3, size) // never longer than three screens
            val w = measureBold(s, size)
            if (w <= room) { if (center) text(c, s, x + room / 2, y, size, u, bold = bold, align = Paint.Align.CENTER, color = color) else text(c, s, x, y, size, u, bold = bold, color = color); return }
            val travel = w - room
            val speed = 90f // pixels a second
            val rest = 1500L
            val moveMs = (travel / speed * 1000).toLong()
            val t = System.currentTimeMillis() % (2 * (rest + moveMs))
            val off = when {
                t < rest -> 0f
                t < rest + moveMs -> (t - rest) / 1000f * speed
                t < 2 * rest + moveMs -> travel
                else -> travel - (t - 2 * rest - moveMs) / 1000f * speed
            }.coerceIn(0f, travel)
            c.save(); c.clipRect(x * u, (y - size * 1.1f) * u, (x + room) * u, (y + size * 0.4f) * u)
            text(c, s, x - off, y, size, u, bold = bold, color = color)
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

        private fun titleText(c: Canvas, s: String, u: Float) = marquee(c, s, 20f, 85f, 30f, 440f, u, bold = true, color = lcd, center = true)

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

        private fun home(c: Canvas, i: HomeInfo, u: Float) {
            // deliberately no mode name or banner: in School this must look like any plain phone
            text(c, i.time, 240f, 270f, 120f, u, bold = true, align = Paint.Align.CENTER)
            text(c, i.date, 240f, 332f, 36f, u, align = Paint.Align.CENTER)
            badges(c, i, u)
            softKeys(c, i.softLeft, model?.softKeys?.second ?: i.softRight, u) // the model knows whether the right key is Music or Contacts
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
                val prefix = style?.prefix ?: if (idx < 9) "${idx + 1}  " else if (idx == 9 && m.screen == Screen.MENU) "0  " else "   "
                val tx = style?.tx ?: 20f
                val room = style?.room ?: 440f
                val label = ellipsize(prefix + row, room, 32f)
                if (idx == m.selected) {
                    c.drawRect(0f, top * u, width.toFloat(), (top + rowH - 4) * u, fill)
                    marquee(c, prefix + row, tx, top + 42f, 32f, room, u, bold = true, color = lcd)
                } else text(c, label, tx, top + 42f, 32f, u)
                chrome.rowExtras(this, c, m, idx, top, idx == m.selected, u)
                // the Music menu shows what each provider is doing: a play triangle while it plays, two bars while it is paused
                m.rowMarks.getOrNull(idx)?.takeIf { it > 0 }?.let { mark ->
                    val cy = top + 27f; val col = if (idx == m.selected) lcd else ink
                    p.color = col
                    if (mark == 2) { val t = android.graphics.Path().apply { moveTo(432f * u, (cy - 11) * u); lineTo(454f * u, cy * u); lineTo(432f * u, (cy + 11) * u); close() }; c.drawPath(t, p.also { it.style = Paint.Style.FILL }) }
                    else { c.drawRect(434f * u, (cy - 11) * u, 441f * u, (cy + 11) * u, p.also { it.style = Paint.Style.FILL }); c.drawRect(447f * u, (cy - 11) * u, 454f * u, (cy + 11) * u, p) }
                    p.color = ink
                }
            }
            if (m.rows.size > visible) chrome.scrollBar(this, c, first, m.rows.size, visible, u)
            if (m.rows.isEmpty()) text(c, if (m.screen == Screen.WHATSAPP) "No chats yet" else if (m.screen == Screen.PLAYLISTS || m.screen == Screen.DEVICES || m.screen == Screen.SEARCHRES) m.spStatus.ifEmpty { "Empty" } else "Empty", 240f, 300f, 30f, u, align = Paint.Align.CENTER)
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
                if (on) c.drawRect(tile, fill) else c.drawRect(tile, stroke)
                PixelIcons.draw(c, m.iconFor(item), (x + (GRID_TILE_W - 64f) / 2) * u, (y + (GRID_TILE_H - 64f) / 2) * u, 4f * u, if (on) lcd else ink, if (on) ink else lcd)
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
            titleBar(c, m.title, u)
            val lines = wrap(msg.body)
            val whenText = SimpleDateFormat(if (sameDay(msg.date)) "HH:mm" else "d MMM HH:mm", Locale.UK).format(Date(msg.date))
            val pos = "${m.selected + 1}/${convo.msgs.size}"
            val who = if (msg.mine) "Me" else msg.sender.ifBlank { convo.name }
            val room = 448f - measureBold(pos, 24f) - measureBold("  $whenText", 24f)
            text(c, fit(who, room, 24f) + "  " + whenText, 16f, 134f, 24f, u, bold = true)
            text(c, pos, 464f, 134f, 24f, u, align = Paint.Align.RIGHT)
            val page = lines.drop(m.scroll).take(m.pageLines)
            page.forEachIndexed { n, s -> text(c, s, 16f, 176f + n * 36f, BODY, u) }
            if (m.scroll > 0) arrowV(c, 456f, 150f, -1, u)
            if (m.scroll + m.pageLines < lines.size) arrowV(c, 456f, 548f, 1, u)
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        /** Cuts a long line to fit the screen, ending in "..." (previews of messages are often longer than one line). */
        override fun ellipsize(s: String, room: Float, size: Float): String {
            val paint = bodyPaint(); paint.textSize = size * (if (Fonts.byId(model?.font).id == "vt323") 1.3f else 1f); paint.isFakeBoldText = true
            val fits = paint.measureText(s) <= room
            if (fits) { paint.isFakeBoldText = false; return s }
            var t = s
            while (t.length > 1 && paint.measureText("$t...") > room) t = t.dropLast(1)
            paint.isFakeBoldText = false
            return "$t..."
        }

        override fun measureBold(s: String, size: Float): Float {
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
                val field = if (m.writeSearch) "Search_" else if (m.writeWifi != null) "Password_" else "To: " + m.writeLabel.ifBlank { m.writeTo } + "_"
                if (chrome.fieldTitle(field)) { /* the chrome shows it in its title bar */ }
                else {
                    c.drawRect(0f, 47 * u, width.toFloat(), 99 * u, fill)
                    text(c, field, 16f, 85f, 30f, u, bold = true, color = lcd)
                }
            } else {
                text(c, if (m.writeSearch) "Search" else if (m.writeWifi != null) "Password: " + m.writeWifi!!.ssid else "To: " + m.writeLabel.ifBlank { m.writeTo }, 16f, 85f, 30f, u, bold = true)
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
            titleBar(c, m.title, u)
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
                    text(c, m.waPairNumber + "_", 240f, 300f, size / (if (Fonts.byId(m.font).id == "vt323") 1.3f else 1f), u, bold = true, align = Paint.Align.CENTER)
                    text(c, "e.g. 447700900123", 240f, 360f, 24f, u, align = Paint.Align.CENTER)
                }
            }
            softKeys(c, m.softKeys.first, m.softKeys.second, u)
        }

        private fun notice(c: Canvas, s: String, u: Float) {
            if (chrome.notice(this, c, s, u)) return
            c.drawRect(30 * u, 250 * u, 450 * u, 350 * u, fill)
            text(c, s, 240f, 312f, 32f, u, bold = true, align = Paint.Align.CENTER, color = lcd)
        }

        private fun plural(n: Int, what: String) = "$n $what" + if (n == 1) "" else "s"
    }
}

/** The skins. They share the painted screens and differ only in their chrome. */
class NokiaSkin : PaintedSkin(NokiaChrome, "nokia-list")

class MacSkin : PaintedSkin(MacChrome(), "classic-mac")
