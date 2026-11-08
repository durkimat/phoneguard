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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(ev: KeyEvent): Boolean {
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
