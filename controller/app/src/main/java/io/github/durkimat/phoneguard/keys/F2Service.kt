package io.github.durkimat.phoneguard.keys

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import io.github.durkimat.phoneguard.Log
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
    private var downAt = 0L
    private var lastTap = 0L
    private var longFired = false
    private val longPress = Runnable { longFired = true; if (!CameraActivity.isFront()) Torch.toggle(this) }

    /** The package whose window came to the front last (window titles and contents are never read). */
    @Volatile private var frontPkg = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = event.packageName?.toString().orEmpty()
            // pop-ups and the system's own windows do not count as the app in front
            if (p.isNotEmpty() && p != "android" && p != "com.android.systemui") frontPkg = p
        }
    }
    override fun onInterrupt() {}

    /**
     * The green key from another app: Android would send a "call button" intent that offers the stock dialler (and its "make
     * default" nag). Open our dial pad instead. On our own screens, and while a call is ringing or live, the key is left alone.
     */
    private fun callKey(ev: KeyEvent): Boolean {
        Log.log(this, "call key ${ev.action} info=${CallState.info != null} front=$frontPkg")
        if (CallState.info != null || frontPkg == packageName) return false
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
            try { startActivity(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(LauncherActivity.EXTRA_DIAL, "")) }
            catch (e: Exception) { Log.log(this, "call key: ${e.javaClass.simpleName}") }
        }
        return true
    }

    override fun onKeyEvent(ev: KeyEvent): Boolean {
        if (ev.keyCode == KeyEvent.KEYCODE_CALL) return callKey(ev)
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
