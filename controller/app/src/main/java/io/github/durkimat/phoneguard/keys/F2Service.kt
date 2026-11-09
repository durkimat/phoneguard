package io.github.durkimat.phoneguard.keys

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.Usage
import io.github.durkimat.phoneguard.camera.CameraActivity
import io.github.durkimat.phoneguard.incall.CallState
import io.github.durkimat.phoneguard.launcher.LauncherActivity
import io.github.durkimat.phoneguard.launcher.LauncherData

/**
 * The side button of the J36 sends F2, which Android ignores. This service (a device owner can switch it on without a prompt) sees
 * the key whatever app is in front while the screen is on: two quick presses open the camera, a long press switches the torch,
 * and inside the camera one press is the shutter. It reads no screen content: it only watches this one key.
 */
class F2Service : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val volume by lazy { VolumeBar(this) }
    private val cover by lazy { ScreenCover(this) }

    companion object {
        @Volatile private var instance: F2Service? = null
        /** Cover the whole screen with the theme panel (false removes it). Returns false when the service is not running, so the caller shows nothing special. */
        fun cover(on: Boolean, message: String = "Starting Spotify..."): Boolean {
            val s = instance ?: return false
            if (on) s.cover.show(message) else s.cover.hide()
            return true
        }
    }
    private var downAt = 0L
    private var lastTap = 0L
    private var longFired = false
    private val longPress = Runnable { longFired = true; if (!CameraActivity.isFront()) Torch.toggle(this) }

    /** The package whose window came to the front last (window titles and contents are never read). */
    @Volatile private var frontPkg = ""

    /** True while Android's Bluetooth pairing dialog is on screen and our panel stands over it (see [onPairingDialog]). */
    @Volatile private var pairing = false
    private val pairingClass = "com.android.settings.bluetooth.BluetoothPairingDialog"

    /**
     * Android asks "Pair with X?" in a touch dialog. Here the dialog is covered by a panel in the phone's own style: what it asks
     * (the name and the pairing code to compare), OK to pair, Back to cancel. Only that one dialog's window is read, and only to
     * show its text and press its own two buttons.
     */
    private fun onPairingDialog() {
        main.postDelayed({
            try {
                val root = rootInActiveWindow ?: return@postDelayed
                val texts = mutableListOf<String>()
                fun walk(n: android.view.accessibility.AccessibilityNodeInfo) {
                    // "Allow contacts and call history": ticked for the person, as most people leave it (it does nothing for audio devices)
                    if (n.className?.toString()?.contains("CheckBox") == true && n.isCheckable && !n.isChecked) n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    val t = n.text?.toString()?.trim().orEmpty()
                    if (t.isNotEmpty() && n.className?.toString()?.contains("Button") != true && n.className?.toString()?.contains("CheckBox") != true && n.className?.toString()?.contains("Switch") != true) texts += t
                    for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
                }
                walk(root)
                pairing = true
                cover.showPanel(texts.distinct().take(4).ifEmpty { listOf("Pair this device?") }, "Pair", "Cancel", 60_000)
            } catch (e: Exception) { Log.log(this, "pairing panel: ${e.javaClass.simpleName}") }
        }, 350)
    }

    private fun pressPairingButton(positive: Boolean) {
        try {
            val id = if (positive) "android:id/button1" else "android:id/button2"
            rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) { Log.log(this, "pairing button: ${e.javaClass.simpleName}") }
        pairing = false; cover.hide()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = event.packageName?.toString().orEmpty()
            if (event.className?.toString() == pairingClass) { onPairingDialog(); return }
            if (pairing && p != packageName) { pairing = false; cover.hide() } // the dialog went away by itself
            // pop-ups and the system's own windows do not count as the app in front
            if (p.isNotEmpty() && p != "android" && p != "com.android.systemui") frontPkg = p
        }
    }
    override fun onInterrupt() {}

    /** Debug builds only: lets a test show the volume bar without a physical key (injected keys never reach this service). */
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (io.github.durkimat.phoneguard.BuildConfig.DEBUG) {
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: Intent?) { volume.press(i?.getBooleanExtra("up", true) ?: true) }
            }
            registerReceiver(r, android.content.IntentFilter("io.github.durkimat.phoneguard.DEBUG_VOLUME"), android.content.Context.RECEIVER_EXPORTED)
            val stopper = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: Intent?) { val pkg = i?.getStringExtra("pkg") ?: return; Thread { Log.log(this@F2Service, "debug stop $pkg: " + io.github.durkimat.phoneguard.IdleReaper.stop(this@F2Service, pkg)) }.start() }
            }
            val lister = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: Intent?) {
                    val am = this@F2Service.getSystemService(android.app.ActivityManager::class.java)
                    val procs = am.runningAppProcesses ?: emptyList()
                    Log.log(this@F2Service, "running processes seen: ${procs.size}: " + procs.joinToString { it.processName + ":" + it.importance })
                }
            }
            registerReceiver(lister, android.content.IntentFilter("io.github.durkimat.phoneguard.DEBUG_RUNNING"), android.content.Context.RECEIVER_EXPORTED)
            val badger = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: Intent?) {
                    val n = i?.getIntExtra("n", 0) ?: 0
                    val kinds = listOf(io.github.durkimat.phoneguard.launcher.BadgeKind.SMS, io.github.durkimat.phoneguard.launcher.BadgeKind.WHATSAPP, io.github.durkimat.phoneguard.launcher.BadgeKind.MISSED, io.github.durkimat.phoneguard.launcher.BadgeKind.NOTES, io.github.durkimat.phoneguard.launcher.BadgeKind.MUSIC)
                    LauncherData.debugBadges = if (n <= 0) null else kinds.take(n).map { io.github.durkimat.phoneguard.launcher.Badge(it, 3) }
                }
            }
            registerReceiver(badger, android.content.IntentFilter("io.github.durkimat.phoneguard.DEBUG_BADGES"), android.content.Context.RECEIVER_EXPORTED)
            registerReceiver(stopper, android.content.IntentFilter("io.github.durkimat.phoneguard.DEBUG_STOP"), android.content.Context.RECEIVER_EXPORTED)
        }
    }

    /**
     * The green key from another app: Android would send a "call button" intent that offers the stock dialler (and its "make
     * default" nag). Open our dial pad instead. On our own screens, and while a call is ringing or live, the key is left alone.
     */
    private fun callKey(ev: KeyEvent): Boolean {
        Log.log(this, "call key ${ev.action} info=${CallState.info != null} front=${Usage.foreground(this)}/$frontPkg")
        if (CallState.info != null || (Usage.foreground(this) ?: frontPkg) == packageName) return false
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
            try { startActivity(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(LauncherActivity.EXTRA_DIAL, "")) }
            catch (e: Exception) { Log.log(this, "call key: ${e.javaClass.simpleName}") }
        }
        return true
    }

    override fun onKeyEvent(ev: KeyEvent): Boolean {
        if (pairing && (ev.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || ev.keyCode == KeyEvent.KEYCODE_ENTER || ev.keyCode == KeyEvent.KEYCODE_MENU || ev.keyCode == KeyEvent.KEYCODE_BACK)) {
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) pressPairingButton(ev.keyCode != KeyEvent.KEYCODE_BACK)
            return true
        }
        if (ev.keyCode == KeyEvent.KEYCODE_CALL) return callKey(ev)
        if (ev.keyCode == KeyEvent.KEYCODE_VOLUME_UP || ev.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // the camera uses the volume keys as its shutter; everywhere else our own themed bar replaces Android's slider
            if (CameraActivity.isFront()) return false
            if (ev.action == KeyEvent.ACTION_DOWN) volume.press(ev.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            return true
        }
        if (ev.keyCode != KeyEvent.KEYCODE_F2) return false
        when (ev.action) {
            KeyEvent.ACTION_DOWN -> if (ev.repeatCount == 0) {
                downAt = ev.eventTime; longFired = false
                main.removeCallbacks(longPress); main.postDelayed(longPress, 600)
            }
            KeyEvent.ACTION_UP -> {
                main.removeCallbacks(longPress)
                if (longFired) return true
                val now = ev.eventTime
                if (CameraActivity.isFront()) { CameraActivity.shutter(); lastTap = 0; return true } // inside the camera one press takes the picture
                if (now - lastTap in 40..700) {
                    lastTap = 0
                    if (CallState.info == null && LauncherData.cameraAllowed(this)) {
                        try { startActivity(Intent(this, CameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Log.log(this, "side button: ${e.javaClass.simpleName}") }
                    }
                } else lastTap = now
            }
        }
        return true // the key is ours: nothing else acts on it
    }
}
