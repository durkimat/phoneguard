package io.github.durkimat.phoneguard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.integration.android.IntentIntegrator
import kotlin.concurrent.thread

/** Status, plus pairing (scan the QR shown in Home Assistant). Everything else is controlled from HA. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { textSize = 16f }
        logView = TextView(this).apply { textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Pair: scan the QR code"
            setOnClickListener {
                IntentIntegrator(this@MainActivity).setPrompt("Scan the QR code shown in Home Assistant")
                    .setBeepEnabled(false).setOrientationLocked(true).initiateScan()
            }
        })
        root.addView(Button(this).apply { text = "Sync now"; setOnClickListener { Link.runAsync(this@MainActivity, "manual") } })
        root.addView(Button(this).apply { text = "Re-check now"; setOnClickListener { Controller.evaluate(this@MainActivity, "manual"); refresh() } })
        root.addView(logView)
        setContentView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(-1, -2)) })
        Log.listener = { ui.post { refresh() } }
    }

    @Deprecated("ZXing's IntentIntegrator still reports through onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val res = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        val text = res?.contents
        if (text == null) { super.onActivityResult(requestCode, resultCode, data); return }
        thread {
            val r = try { Link.pair(this, text) } catch (e: Exception) { "pairing failed: ${e.javaClass.simpleName}: ${e.message}" }
            Log.log(this, "pair: $r")
            if (r.startsWith("ok")) Link.runAsync(this, "after pairing")
            ui.post { refresh() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Dpm.isOwner(this)) GuardService.start(this)
        refresh()
    }

    private fun refresh() {
        val cfg = Store.config(this)
        status.text = buildString {
            appendLine("Phone Guard ${BuildConfig.VERSION_NAME}${if (BuildConfig.DEBUG) " (debug build)" else ""}")
            appendLine("Device owner: ${Dpm.isOwner(this@MainActivity)}")
            appendLine("Now: ${Store.summary(this@MainActivity)}")
            appendLine("Home Assistant: ${Link.summary(this@MainActivity)}")
            if (Link.isPending(this@MainActivity)) appendLine("\nCONFIRM IN HOME ASSISTANT: ${Link.sas(this@MainActivity)}\n")
            appendLine("Config: ${cfg?.let { "v${it.version}, ${it.modes.size} modes" } ?: "none (Emergency mode)"}")
            appendLine("Hidden by us: ${Store.hidden(this@MainActivity).size}")
            appendLine("Grayscale permission: ${Enforcer.canWriteSecure(this@MainActivity)}")
        }
        logView.text = Log.tail(this, 25)
    }
}
