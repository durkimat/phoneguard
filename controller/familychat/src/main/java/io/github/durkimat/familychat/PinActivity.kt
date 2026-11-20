package io.github.durkimat.familychat

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Screen 3: the admin PIN. As many dots as the PIN has digits (four for a four-digit PIN); a wrong PIN slows the next try down. */
class PinActivity : Activity() {
    private var entered = ""
    private lateinit var dots: LinearLayout
    private lateinit var msg: TextView
    private lateinit var open: TextView
    private val gate by lazy { PinGate(Fc.settings) }
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { showLock(); if (gate.lockedSeconds() > 0) h.postDelayed(this, 1000) } }
    private val length get() = Fc.settings.pin?.length ?: 4

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fc.init(this)
        val c = this
        val body = Ui.pad(c).apply { gravity = Gravity.CENTER_HORIZONTAL }
        body.addView(Ui.text(c, "Cancel", 18f, Ui.ACCENT, true).apply { minHeight = Ui.dp(c, 48); gravity = Gravity.CENTER_VERTICAL; isClickable = true; setOnClickListener { finish() } }, Ui.params(w = -2))
        body.addView(Ui.title(c, "Admin").apply { gravity = Gravity.CENTER }, Ui.params(c = c, top = 16))
        body.addView(Ui.text(c, "Enter the admin PIN", 17f, Ui.MUTED).apply { gravity = Gravity.CENTER }, Ui.params(c = c, top = 8))
        dots = LinearLayout(c).apply { gravity = Gravity.CENTER; contentDescription = "PIN digits entered" }
        body.addView(dots, Ui.params(c = c, top = 24))
        val grid = GridLayout(c).apply { columnCount = 3 }
        for (k in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "Del")) {
            val key = Ui.text(c, k, 24f, Ui.INK, true).apply {
                gravity = Gravity.CENTER; contentDescription = k.ifEmpty { null }; if (k.isNotEmpty()) { background = Ui.shape(c, Ui.CARD, 16, Ui.BORDER); isClickable = true; setOnClickListener { press(k) } }
            }
            grid.addView(key, GridLayout.LayoutParams().apply { width = Ui.dp(c, 88); height = Ui.dp(c, 64); setMargins(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6)) })
        }
        body.addView(grid, Ui.params(w = -2, c = c, top = 24))
        msg = Ui.text(c, "", 16f, Ui.DANGER).apply { gravity = Gravity.CENTER }
        body.addView(msg, Ui.params(c = c, top = 12))
        open = Ui.button(c, "Open admin") { submit() }.apply { visibility = android.view.View.GONE }
        body.addView(open, Ui.params(c = c, top = 12))
        body.addView(Ui.text(c, "Ask a parent if you need to change something.", 15f, Ui.MUTED).apply { gravity = Gravity.CENTER }, Ui.params(c = c, top = 20))
        setContentView(Ui.screen(c, body))
        draw(); tick.run()
    }

    override fun onDestroy() { h.removeCallbacks(tick); super.onDestroy() }

    private fun press(k: String) {
        if (gate.lockedSeconds() > 0) return
        if (k == "Del") entered = entered.dropLast(1) else if (entered.length < length) entered += k
        draw()
        if (entered.length == length && length > 4) { /* longer PINs wait for the button */ }
    }

    private fun draw() {
        val c = this
        dots.removeAllViews()
        for (i in 0 until length) dots.addView(android.view.View(c).apply {
            background = Ui.shape(c, if (i < entered.length) Ui.ACCENT else 0x00000000, 12, Ui.ACCENT)
        }, LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)).also { it.setMargins(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0) })
        open.visibility = if (entered.length == length) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showLock() {
        val left = gate.lockedSeconds()
        if (left > 0) msg.text = "Too many tries. Wait ${if (left >= 60) "${(left + 59) / 60} min" else "$left s"}."
        else if (msg.text.startsWith("Too many")) msg.text = ""
    }

    private fun submit() {
        when (val r = gate.attempt(entered)) {
            is PinGate.Result.Ok -> { startActivity(Intent(this, AdminActivity::class.java)); finish() }
            is PinGate.Result.Wrong -> { entered = ""; draw(); msg.text = "That PIN is not right."; if (r.lockedSeconds > 0) tick.run() }
            is PinGate.Result.Locked -> { entered = ""; draw(); tick.run() }
        }
    }
}
