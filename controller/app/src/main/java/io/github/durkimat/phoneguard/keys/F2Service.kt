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
 * the key whatever app is in front while the screen is on: one press opens the camera, two quick presses switch the torch,
 * a hold is for speaking (see core/F2Gesture), and inside the camera one press is the shutter. It reads no screen content: it only watches this one key.
 */
class F2Service : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val volume by lazy { VolumeBar(this) }
    private val cover by lazy { ScreenCover(this) }
    private val pin by lazy { PinCover(this, cover) }

    companion object {
        @Volatile private var instance: F2Service? = null

        /**
         * Used by the "get an app through the store" job ([io.github.durkimat.phoneguard.StoreInstall]): the package and the words (texts and descriptions) of the
         * window in front, or null when the service is not running or nothing can be read. Only called while that job runs.
         */
        fun readScreen(): Pair<String, List<String>>? = try {
            val root = instance?.rootInActiveWindow
            if (root == null) null else {
                val out = ArrayList<String>()
                fun walk(n: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
                    if (depth > 40 || out.size > 400) return
                    n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
                    n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
                    for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
                }
                walk(root, 0)
                (root.packageName?.toString().orEmpty()) to out
            }
        } catch (_: Exception) { null }

        /** Press the button whose text (or description) is [label]: the exact word first, then one that contains it. True when something was pressed. */
        fun clickLabel(label: String): Boolean = try {
            val root = instance?.rootInActiveWindow
            if (root == null) false else {
                val all = ArrayList<android.view.accessibility.AccessibilityNodeInfo>()
                fun walk(n: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
                    if (depth > 40 || all.size > 400) return
                    all += n
                    for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
                }
                walk(root, 0)
                fun words(n: android.view.accessibility.AccessibilityNodeInfo) = listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).map { it.trim() }
                val hit = all.firstOrNull { n -> words(n).any { it.equals(label, ignoreCase = true) } }
                    ?: all.firstOrNull { n -> words(n).any { it.contains(label, ignoreCase = true) } }
                var n = hit
                var done = false
                while (n != null && !done) { if (n.isClickable && n.isEnabled) done = n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) else n = n.parent }
                done
            }
        } catch (_: Exception) { false }

        /** Whether the service is running (the job needs it to press the buttons). */
        fun running(): Boolean = instance != null

        /** The package in front (for deciding whether a text box of ours or of another app is being used). */
        fun frontPackage(): String = instance?.frontPkg.orEmpty()

        /** True when another app has a text box with the focus (not a password box): a spoken dictation can go there. */
        fun editableFocused(): Boolean = try { instance?.rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)?.let { it.isEditable && !it.isPassword } == true } catch (_: Exception) { false }

        /**
         * Put [text] into the focused text box at the cursor: set the whole text (what was there plus the new words) and put the cursor after them;
         * if the app refuses that, copy the words and ask the box to paste them. False when there is no suitable box.
         */
        fun insertText(text: String): Boolean = try {
            val svc = instance
            val node = svc?.rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            if (svc == null || node == null || !node.isEditable || node.isPassword) false else {
                val cur = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
                val at = node.textSelectionStart.let { if (it in 0..cur.length) it else cur.length }
                val (full, pos) = io.github.durkimat.phoneguard.voice.VoiceText.insert(cur, at, text)
                val set = android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, full) }
                if (node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, set)) {
                    val sel = android.os.Bundle().apply { putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos); putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos) }
                    node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
                    true
                } else {
                    val cm = svc.getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("", text.trim()))
                    val ok = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)
                    svc.main.postDelayed({ try { cm.clearPrimaryClip() } catch (_: Exception) { } }, 3000) // a dictation is not left on the clipboard
                    ok
                }
            }
        } catch (_: Exception) { false }
        /** Cover the whole screen with the theme panel (false removes it). Returns false when the service is not running, so the caller shows nothing special. */
        fun cover(on: Boolean, message: String = "Starting Spotify..."): Boolean {
            val s = instance ?: return false
            if (on) s.cover.show(message) else s.cover.hide()
            return true
        }
    }
    private val f2 = io.github.durkimat.phoneguard.core.F2Gesture()
    private fun f2Do(outs: List<io.github.durkimat.phoneguard.core.F2Gesture.Out>) { outs.forEach { F2Actions.run(this, it) } }
    private val f2Timer = object : Runnable {
        override fun run() {
            f2Do(f2.timer(android.os.SystemClock.uptimeMillis(), F2Actions.policy(this@F2Service)))
            f2Schedule()
        }
    }
    /** Ask for the next timer the gesture machine wants (one at a time); let go of the proximity sensor once the gesture is decided. */
    private fun f2Schedule() {
        main.removeCallbacks(f2Timer)
        val d = f2.deadline
        if (d >= 0) main.postDelayed(f2Timer, (d - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0)) else Proximity.release(this)
    }

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
        pairing = true
        cover.showPanel(listOf("Pair this device?"), "Pair", "Cancel", 60_000) // up at once, so the touch dialog is never seen; filled in below
        main.postDelayed({
            try {
                val root = rootInActiveWindow ?: return@postDelayed
                // only a dialog that has the two pairing buttons counts (not any other Settings window)
                if (pairButton(root, true) == null || pairButton(root, false) == null) { pairing = false; cover.hide(); return@postDelayed }
                val texts = mutableListOf<String>()
                fun walk(n: android.view.accessibility.AccessibilityNodeInfo) {
                    // "Also allow access to contacts and call history": switched on for the person, as most people leave it (it does nothing for audio devices)
                    val cls = n.className?.toString().orEmpty()
                    if ((cls.contains("Switch") || cls.contains("CheckBox")) && n.isCheckable && !n.isChecked) n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    val t = n.text?.toString()?.trim().orEmpty()
                    if (t.isNotEmpty() && !cls.contains("Button") && !cls.contains("Switch") && !cls.contains("CheckBox") && !t.startsWith("Also allow") && !t.startsWith("Info will be used")) texts += t
                    for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
                }
                walk(root)
                pairing = true
                cover.showPanel(texts.distinct().take(4).ifEmpty { listOf("Pair this device?") }, "Pair", "Cancel", 60_000)
            } catch (e: Exception) { Log.log(this, "pairing panel: ${e.javaClass.simpleName}") }
        }, 350)
    }

    /** The dialog's Pair (positive) or Cancel button: Settings names them positive_button and negative_button; plain Android dialogs button1 and button2. */
    private fun pairButton(root: android.view.accessibility.AccessibilityNodeInfo, positive: Boolean): android.view.accessibility.AccessibilityNodeInfo? =
        listOf(if (positive) "com.android.settings:id/positive_button" else "com.android.settings:id/negative_button", if (positive) "android:id/button1" else "android:id/button2")
            .firstNotNullOfOrNull { root.findAccessibilityNodeInfosByViewId(it)?.firstOrNull() }

    private fun pressPairingButton(positive: Boolean) {
        try {
            rootInActiveWindow?.let { pairButton(it, positive) }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) { Log.log(this, "pairing button: ${e.javaClass.simpleName}") }
        pairing = false
        cover.showPanel(listOf("Pairing..."), "", "", 30_000) // keeps any follow-up dialog hidden until the pairing is settled (see the bond receiver)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = event.packageName?.toString().orEmpty()
            if (event.className?.toString() == pairingClass) { onPairingDialog(); return }
            // the dialog may be named differently: a Settings window soon after we asked to pair, or one that is titled "Pair with ..."
            if (p == "com.android.settings" && !pairing) {
                val sinceAsk = System.currentTimeMillis() - io.github.durkimat.phoneguard.net.BluetoothControl.pairAskedAt
                if (sinceAsk < 90_000 || event.text.any { it.toString().startsWith("Pair", ignoreCase = true) }) { onPairingDialog(); return }
            }
            if (pairing && p != packageName) { pairing = false; cover.hide() } // the dialog went away by itself
            // pop-ups and the system's own windows do not count as the app in front
            if (p.isNotEmpty() && p != packageName) Log.log(this, "window: $p/${event.className}") // the pop-up detector: any other app's or the system's window that comes to the front
            if (p.isNotEmpty() && p != "android" && p != "com.android.systemui") frontPkg = p
            // SIM method: the official WhatsApp is not for the child to use (a link may open it): send them home unless a setup window is running
            if (p == "com.whatsapp" && io.github.durkimat.phoneguard.whatsapp.SetupRunner.officialBlocked(this)) { Log.log(this, "official WhatsApp opened: sent home"); performGlobalAction(GLOBAL_ACTION_HOME) }
        }
    }
    override fun onInterrupt() {}

    /** Debug builds only: lets a test show the volume bar without a physical key (injected keys never reach this service). */
    private var bondReceiver: android.content.BroadcastReceiver? = null

    override fun onDestroy() {
        try { pin.stop() } catch (_: Exception) { }
        bondReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) { } }; bondReceiver = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        pin.start()
        if (bondReceiver != null) return // connected again without being destroyed: the receivers below are already registered
        // the pairing is settled (paired, or given up): take the panel down
        val bond = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: Intent?) {
                val st = i?.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, -1) ?: return
                if (st == android.bluetooth.BluetoothDevice.BOND_BONDED || st == android.bluetooth.BluetoothDevice.BOND_NONE) { pairing = false; main.postDelayed({ cover.hide() }, 400) }
            }
        }
        bondReceiver = bond
        registerReceiver(bond, android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED), android.content.Context.RECEIVER_EXPORTED)
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
        if (pin.shown && pin.onKey(ev)) return true   // the PIN panel: only Emergency is ours, every other key also reaches the real lock screen
        if (pairing && (ev.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || ev.keyCode == KeyEvent.KEYCODE_ENTER || ev.keyCode == KeyEvent.KEYCODE_MENU || ev.keyCode == KeyEvent.KEYCODE_BACK)) {
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) pressPairingButton(ev.keyCode != KeyEvent.KEYCODE_BACK)
            return true
        }
        // an alarm or timer is sounding: every key quiets it, whichever window has the focus (the ring screen may be hidden behind another)
        if (io.github.durkimat.phoneguard.alarm.RingState.sounding && ev.keyCode != KeyEvent.KEYCODE_POWER) {
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                val stop = io.github.durkimat.phoneguard.launcher.KeyMap.of(ev.keyCode)?.first.let { it == io.github.durkimat.phoneguard.launcher.Key.SOFT_RIGHT || it == io.github.durkimat.phoneguard.launcher.Key.END }
                Log.log(this, "alarms: key ${ev.keyCode} while ringing: ${if (stop) "stop" else "snooze/stop"}")
                if (stop) io.github.durkimat.phoneguard.alarm.RingService.stop(this) else io.github.durkimat.phoneguard.alarm.RingService.snooze(this)
            }
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
        // inside our own camera one press takes the picture at once, with no waiting for a second one
        if (CameraActivity.isFront()) { if (ev.action == KeyEvent.ACTION_UP) CameraActivity.shutter(); return true }
        val now = android.os.SystemClock.uptimeMillis()
        when (ev.action) {
            KeyEvent.ACTION_DOWN -> if (ev.repeatCount == 0) { Proximity.watch(this); f2Do(f2.down(now, F2Actions.policy(this))); f2Schedule() }
            KeyEvent.ACTION_UP -> { f2Do(f2.up(now, F2Actions.policy(this))); f2Schedule() }
        }
        return true // the key is ours: nothing else acts on it
    }
}
