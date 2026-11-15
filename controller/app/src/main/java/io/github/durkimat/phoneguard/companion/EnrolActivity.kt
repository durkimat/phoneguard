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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        root.addView(TextView(this).apply { text = "Connect to the family companion"; textSize = 20f })
        root.addView(Button(this).apply {
            text = "Scan the QR code"
            setOnClickListener { IntentIntegrator(this@EnrolActivity).setPrompt("Scan the QR code from the setup tool").setBeepEnabled(false).setOrientationLocked(true).initiateScan() }
        })
        root.addView(TextView(this).apply { text = "or type it in:" })
        address = EditText(this).apply { hint = "Address (e.g. phoneguard-x7k2.jane)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; setSingleLine() }
        code = EditText(this).apply { imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE; hint = "Code (ABCDE-FGHJK)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; setSingleLine() }
        root.addView(address); root.addView(code)
        val connect = { begin(EnrolPayload.fromTyped(address.text.toString(), code.text.toString())) }
        code.setOnEditorActionListener { _, _, _ -> connect(); true }
        root.addView(Button(this).apply { text = "Connect"; setOnClickListener { connect() } })
        root.addView(Button(this).apply {
            text = "Disconnect from the family companion"
            setOnClickListener { polling = false; WorkerLink.disconnect(this@EnrolActivity); matchView.text = ""; info.text = "Disconnected." }
        })
        matchView = TextView(this).apply { textSize = 48f; gravity = android.view.Gravity.CENTER }
        info = TextView(this).apply { textSize = 15f }
        root.addView(matchView); root.addView(info)
        setContentView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(-1, -2)) })
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
