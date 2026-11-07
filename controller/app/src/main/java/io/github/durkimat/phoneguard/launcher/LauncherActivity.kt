package io.github.durkimat.phoneguard.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.view.KeyEvent
import android.view.WindowManager
import io.github.durkimat.phoneguard.Log

/**
 * A keypad-driven Home screen. It owns no behaviour of its own: [LauncherModel] decides what a key
 * does and a [LauncherSkin] draws the result. Touch is ignored.
 */
class LauncherActivity : Activity() {
    private val skin: LauncherSkin = NokiaSkin()
    private val model = LauncherModel()
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 20_000) } }

    private val changes = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(skin.create(this))
        refresh()
    }

    override fun onResume() {
        super.onResume()
        model.goHome() // coming back to the launcher always shows the home screen
        refresh()
        registerReceiver(changes, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        }.also { f -> f.addAction(Intent.ACTION_TIME_CHANGED) }, Context.RECEIVER_EXPORTED)
        ui.postDelayed(tick, 20_000)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        try { unregisterReceiver(changes) } catch (_: Exception) { }
        super.onPause()
    }

    private fun refresh() {
        model.setItems(LauncherData.items(this))
        model.contactsPkg = LauncherData.contactsPackage(this)
        skin.render(model, LauncherData.info(this))
    }

    private fun key(code: Int): Pair<Key, Int>? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> Key.UP to -1
        KeyEvent.KEYCODE_DPAD_DOWN -> Key.DOWN to -1
        KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT to -1
        KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT to -1
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> Key.OK to -1
        KeyEvent.KEYCODE_MENU -> Key.SOFT_LEFT to -1
        KeyEvent.KEYCODE_BACK -> Key.SOFT_RIGHT to -1
        KeyEvent.KEYCODE_CALL -> Key.CALL to -1
        KeyEvent.KEYCODE_ENDCALL -> Key.END to -1
        KeyEvent.KEYCODE_STAR -> Key.STAR to -1
        KeyEvent.KEYCODE_POUND -> Key.POUND to -1
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> Key.DIGIT to (code - KeyEvent.KEYCODE_0)
        else -> null
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val k = key(ev.keyCode) ?: return super.dispatchKeyEvent(ev)
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) perform(model.onKey(k.first, k.second))
        return true // consume the key up as well so Back never leaves the launcher
    }

    private fun perform(a: Action) {
        when (a) {
            Action.None -> {}
            Action.Redraw -> refresh()
            is Action.Launch -> launch(a.pkg)
            is Action.Dial -> start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(a.digits))))
            Action.CallKey -> start(Intent(Intent.ACTION_VIEW, CallLog.Calls.CONTENT_URI).setType(CallLog.Calls.CONTENT_TYPE))
        }
    }

    private fun launch(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return Log.log(this, "launcher: no launch intent for $pkg")
        start(i)
    }

    private fun start(i: Intent) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Log.log(this, "launcher: could not start ${i.action}: ${e.javaClass.simpleName}") }
    }
}
