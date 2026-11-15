package io.github.durkimat.phoneguard.keys

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.TelecomManager
import android.view.KeyEvent
import io.github.durkimat.phoneguard.Log

/**
 * The PIN screen in the phone's own theme. Android's real lock screen (which keeps the data encrypted and checks the PIN) keeps running
 * underneath and keeps getting every key; this panel stands over it in the launcher's colours and font, showing the PIN as stars,
 * with Clear (the Back key: the lock screen clears its entry too) and Emergency (press twice: calls 112). It never sees the PIN's digits as
 * anything but a count, and it checks nothing: when the real lock screen unlocks, the panel goes away. If this service is off, Android's
 * own pad simply shows.
 */
class PinCover(private val svc: AccessibilityService, private val cover: ScreenCover) {
    private val ui = Handler(Looper.getMainLooper())
    private val km by lazy { svc.getSystemService(KeyguardManager::class.java) }
    private val pm by lazy { svc.getSystemService(PowerManager::class.java) }

    @Volatile var shown = false; private set
    private var count = 0
    private var note = ""
    private var wrongRun = 0
    private var lockedUntil = 0L
    private var sosAt = 0L

    private val tick = object : Runnable {
        override fun run() { update(); if (pm.isInteractive) ui.postDelayed(this, 400) }
    }

    private var screenReceiver: BroadcastReceiver? = null

    /** Takes the screen on/off receiver down again (the service is going away), so it is not leaked. */
    fun stop() { screenReceiver?.let { try { svc.unregisterReceiver(it) } catch (_: Exception) { } }; screenReceiver = null; ui.removeCallbacks(tick) }

    fun start() {
        if (screenReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    Intent.ACTION_SCREEN_ON -> { ui.removeCallbacks(tick); ui.post(tick) }
                    Intent.ACTION_SCREEN_OFF -> { ui.removeCallbacks(tick); hide() }
                }
            }
        }
        screenReceiver = r
        svc.registerReceiver(r, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }, Context.RECEIVER_NOT_EXPORTED)
        if (pm.isInteractive) ui.post(tick)
    }

    /** The real lock screen is up and asking for a PIN, password or pattern. */
    private fun locked(): Boolean = try { km.isKeyguardLocked && km.isDeviceSecure && pm.isInteractive } catch (_: Exception) { false }

    private fun update() {
        if (locked()) {
            if (!shown) { shown = true; count = 0; note = ""; wrongRun = 0 }
            render()
        } else if (shown) hide()
    }

    private fun render() {
        val head = note.ifEmpty { "Enter PIN" }
        cover.showPanel(listOf(head, if (count > 0) "*".repeat(count) else " "), "Emergency", "Clear", 600_000)
    }

    private fun hide() { shown = false; count = 0; note = ""; cover.hide() }

    /** True when the key is ours and must not reach Android (only Emergency); every other key is only watched. */
    fun onKey(ev: KeyEvent): Boolean {
        if (!shown || ev.action != KeyEvent.ACTION_DOWN || ev.repeatCount != 0) return false
        val code = ev.keyCode
        when {
            code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 || code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                if (System.currentTimeMillis() >= lockedUntil) { count = (count + 1).coerceAtMost(16); note = ""; render() }
            }
            code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_CLEAR -> { count = (count - 1).coerceAtLeast(0); render() }
            code == KeyEvent.KEYCODE_BACK -> { count = 0; note = ""; render() }
            code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER -> submit()
            code == KeyEvent.KEYCODE_MENU -> { emergency(); return true }
        }
        return false
    }

    /** Enter was pressed: Android checks the PIN; if the lock screen is still up a moment later, it was wrong. */
    private fun submit() {
        if (count == 0) return
        ui.postDelayed({
            if (shown && locked()) {
                wrongRun++
                count = 0
                if (wrongRun >= 5) { note = "Wrong. Wait a while"; lockedUntil = System.currentTimeMillis() + 30_000 } else note = "Wrong PIN"
                render()
            } else if (!locked()) { wrongRun = 0 }
        }, 1_300)
    }

    /** Emergency calls must stay possible from the lock screen: two presses within four seconds dial 112 (the EU number, which also reaches the UK emergency services). */
    private fun emergency() {
        val now = System.currentTimeMillis()
        if (sosAt != 0L && now - sosAt < 4_000) {
            sosAt = 0
            try { svc.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", "112", null), null) }
            catch (e: Exception) { Log.log(svc, "emergency call: ${e.javaClass.simpleName}") }
        } else {
            sosAt = now; note = "Press again to call 112"; render()
            ui.postDelayed({ if (note.startsWith("Press again")) { note = ""; render() } }, 4_000)
        }
    }
}
