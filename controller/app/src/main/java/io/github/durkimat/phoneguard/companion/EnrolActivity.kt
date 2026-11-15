package io.github.durkimat.phoneguard.companion

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.integration.android.IntentIntegrator
import io.github.durkimat.phoneguard.Log
import java.net.URI

/**
 * Enrolment with the family's Cloudflare companion: scan the QR from the setup tool, or type the address and code. The phone then shows
 * a 4-digit number; the parent types it on their page to confirm. Parent-only screen (reached from the status page, not the child's launcher).
 */
class EnrolActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var address: EditText
    private lateinit var code: EditText
    private lateinit var info: TextView
    private lateinit var matchView: TextView
    private var polling = false
    @Volatile private var inFlight = false

    private var lcd = 0; private var ink = 0
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** A full-width bar in the phone's own colours: outlined at rest, filled when it has the focus (so the keypad's arrows show where you are). */
    private fun bar(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 18f; setTextColor(ink); isFocusable = true; minHeight = dp(48); minimumHeight = dp(48)
        background = android.graphics.drawable.StateListDrawable().apply {
            val on = android.graphics.drawable.GradientDrawable().apply { setColor(ink) }
            val off = android.graphics.drawable.GradientDrawable().apply { setColor(lcd); setStroke(dp(2), ink) }
            addState(intArrayOf(android.R.attr.state_focused), on); addState(intArrayOf(android.R.attr.state_pressed), on); addState(intArrayOf(), off)
        }
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()), intArrayOf(lcd, lcd, ink)))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun field(hintText: String, type: Int) = EditText(this).apply {
        hint = hintText; inputType = type; setSingleLine(); textSize = 18f; setTextColor(ink); setHintTextColor((ink and 0x00FFFFFF) or 0x88000000.toInt())
        background = android.graphics.drawable.GradientDrawable().apply { setColor(lcd); setStroke(dp(2), ink) }
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    private fun label(t: String, size: Float, bold: Boolean = false, centre: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(ink); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD); if (centre) gravity = android.view.Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val theme = io.github.durkimat.phoneguard.launcher.Themes.byId(createDeviceProtectedStorageContext().getSharedPreferences("launcher", MODE_PRIVATE).getString("theme", null))
        lcd = theme.lcd; ink = theme.ink
        window.statusBarColor = lcd; window.navigationBarColor = lcd
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)); setBackgroundColor(lcd) }
        root.addView(label("Family companion", 24f, bold = true))
        root.addView(label("Connect this phone to the family's page, or scan the code from the setup tool.", 15f))
        root.addView(bar("Scan the QR code") { IntentIntegrator(this@EnrolActivity).setPrompt("Scan the QR code from the setup tool").setBeepEnabled(false).setOrientationLocked(true).initiateScan() })
        root.addView(label("or type it in:", 15f))
        address = field("Address (e.g. phoneguard-x7k2.jane)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        code = field("Code (ABCDE-FGHJK)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply { imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE }
        root.addView(address); root.addView(code)
        val connect = { begin(EnrolPayload.fromTyped(address.text.toString(), code.text.toString())) }
        code.setOnEditorActionListener { _, _, _ -> connect(); true }
        root.addView(bar("Connect") { connect() })
        root.addView(bar("Disconnect") { polling = false; WorkerLink.disconnect(this@EnrolActivity); matchView.text = ""; info.text = "Disconnected." })
        matchView = label("", 48f, bold = true, centre = true)
        info = label("", 16f)
        root.addView(matchView); root.addView(info)
        setContentView(ScrollView(this).apply { setBackgroundColor(lcd); isFillViewport = true; addView(root, ViewGroup.LayoutParams(-1, -2)) })
        // a bundle pushed by the setup helper (dev route) pre-fills the form
        EnrolStore.pending(this)?.let { b ->
            runCatching { address.setText(URI(b.workerUrl).host ?: "") }; code.setText(b.token)
        }
        if (WorkerLink.pendingMatch(this) != null) { show("Waiting for the parent to confirm."); startPolling() }
        else if (WorkerLink.isPaired(this)) info.text = "Connected: ${WorkerLink.summary(this)}"
    }

    @Deprecated("ZXing's IntentIntegrator still reports through onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val text = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)?.contents
        if (text == null) { super.onActivityResult(requestCode, resultCode, data); return }
        begin(EnrolPayload.parseQr(text).also { if (it == null) info.text = "Unsupported or damaged code." })
    }

    private fun begin(p: EnrolPayload?) {
        if (p == null) { info.text = "Check the address and the code."; return }
        if (io.github.durkimat.phoneguard.Edition.get(this) == io.github.durkimat.phoneguard.EditionState.OPEN) { info.text = "This is an Open phone. To make it a Family phone, erase it first (Settings, Phone type)."; return }
        if (inFlight || polling || WorkerLink.isPaired(this)) return // a second submit (Enter and Done, a double tap) must not start another pairing
        inFlight = true
        info.text = "Connecting to ${p.host} ..."
        WorkerLink.async {
            try {
                val m = WorkerLink.start(this, p)
                ui.post { show("Type this number on the parent page, then wait.", m); startPolling() }
            } catch (e: Exception) {
                Log.log(this, "companion: pairing failed: ${e.message}")
                ui.post { if (!WorkerLink.isPaired(this) && !polling) info.text = "Could not connect: ${e.message}" }
            } finally { inFlight = false }
        }
    }

    private fun show(text: String, match: String? = null) {
        matchView.text = match ?: WorkerLink.pendingMatch(this).orEmpty()
        info.text = text
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        WorkerLink.async { loop() }
    }

    private fun loop() {
        var tries = 0
        while (polling && tries++ < 200) { // about 10 minutes
            when (val r = WorkerLink.pollPair(this)) {
                "paired" -> {
                    EnrolStore.clear(this); polling = false
                    ui.post { matchView.text = ""; info.text = "Connected. Making this a Family phone..." }
                    val why = io.github.durkimat.phoneguard.Edition.becomeFamily(this)
                    WorkerLink.runAsync(this, "after pairing")
                    ui.post { info.text = if (why.isEmpty()) "Connected. The parent can now set this phone up." else "Connected, but: $why" }
                    return
                }
                "waiting" -> {}
                else -> if (r.startsWith("failed")) { polling = false; ui.post { matchView.text = ""; info.text = "Pairing ended ($r). Try again with a new code." }; return }
            }
            Thread.sleep(3_000)
        }
        polling = false
    }

    override fun onDestroy() { polling = false; super.onDestroy() }
}
