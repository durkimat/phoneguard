package io.github.durkimat.phoneguard.incall

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import io.github.durkimat.phoneguard.launcher.Action
import io.github.durkimat.phoneguard.launcher.CallPhase
import io.github.durkimat.phoneguard.launcher.Fonts
import io.github.durkimat.phoneguard.launcher.KeyMap
import io.github.durkimat.phoneguard.launcher.LauncherData
import io.github.durkimat.phoneguard.launcher.LauncherModel
import io.github.durkimat.phoneguard.launcher.NokiaSkin
import io.github.durkimat.phoneguard.launcher.Themes

/** The call screen: the same Nokia look as the launcher, shown over the lock screen. It owns no logic of its own. */
class CallActivity : Activity() {
    private val skin = NokiaSkin()
    private val model = LauncherModel()
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { redraw(); ui.postDelayed(this, 1_000) } }
    private val listener: () -> Unit = { update() }
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = createDeviceProtectedStorageContext().getSharedPreferences("launcher", Context.MODE_PRIVATE)
        model.theme = prefs.getString("theme", Themes.DEFAULT) ?: Themes.DEFAULT
        model.font = prefs.getString("font", Fonts.DEFAULT) ?: Fonts.DEFAULT
        model.speakerShown = prefs.getBoolean("speaker", true)
        setContentView(skin.create(this))
        CallState.listen(listener)
        // the left key under the screen only reaches us if it is remapped, in any mode, for the length of the call
        io.github.durkimat.phoneguard.HardKeys.menuKeyOverlay(this, false)
        LeftKey.listener = { perform(model.onKey(io.github.durkimat.phoneguard.launcher.Key.SOFT_LEFT, -1)) }
        update()
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); update() }

    override fun onResume() {
        super.onResume()
        getSystemService(android.app.NotificationManager::class.java).cancel(CallService.NOTE)
        ui.postDelayed(tick, 1_000)
    }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() {
        CallState.unlisten(listener)
        LeftKey.listener = null
        // give the left key back to whatever the current mode wants
        io.github.durkimat.phoneguard.Controller.evaluateAsync(this, "call screen closed")
        super.onDestroy()
    }

    private fun update() {
        val info = CallState.info
        if (info == null) { finish(); return }
        model.setCall(info)
        redraw()
        if (info.phase == CallPhase.ENDED && !closing) { closing = true; ui.postDelayed({ close() }, 2_000) }
        if (info.phase != CallPhase.ENDED) closing = false
    }

    private fun redraw() { skin.render(model, LauncherData.info(this)) }

    private fun close() { CallState.set(null); finish() }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val k = KeyMap.of(ev.keyCode) ?: return super.dispatchKeyEvent(ev)
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) perform(model.onKey(k.first, k.second))
        return true // keys belong to the call: Back must not leave it
    }

    private fun perform(a: Action) {
        val svc = CallService.instance
        when (a) {
            Action.Redraw -> redraw()
            Action.Answer -> svc?.answer()
            Action.Reject -> svc?.reject()
            Action.EndCall -> svc?.end()
            Action.ToggleSpeaker -> svc?.toggleSpeaker()
            Action.ToggleMute -> svc?.toggleMute()
            is Action.Dtmf -> svc?.dtmf(a.tone)
            is Action.CallVolume -> svc?.volume(a.delta)
            Action.CloseCall -> close()
            else -> {}
        }
        redraw()
    }
}
