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
import io.github.durkimat.phoneguard.VOICE_ASSIST
import io.github.durkimat.phoneguard.WEB_SEARCH

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
        model.theme = prefs().getString("theme", Themes.DEFAULT) ?: Themes.DEFAULT
        setContentView(skin.create(this))
        refresh()
        if (intent?.action == VOICE_ASSIST || intent?.action == WEB_SEARCH) leftKeyPending = true
    }

    /** The left key under the screen arrives as a voice-assist request (see HardKeys). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // onResume follows and resets to the home screen, so remember the key and apply it after that
        if (intent.action == VOICE_ASSIST || intent.action == WEB_SEARCH) leftKeyPending = true
    }

    private var leftKeyPending = false
    private var cameBack = true

    override fun onStop() { cameBack = true; super.onStop() }

    override fun onResume() {
        super.onResume()
        // coming back from another app shows the home screen; a key press while we are in front must not reset it
        if (cameBack) { model.goHome(); cameBack = false }
        refresh()
        if (leftKeyPending) { leftKeyPending = false; perform(model.onKey(Key.SOFT_LEFT, -1)) }
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

    private fun prefs() = createDeviceProtectedStorageContext().getSharedPreferences("launcher", Context.MODE_PRIVATE)

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
            is Action.SaveTheme -> { prefs().edit().putString("theme", a.id).apply(); refresh() }
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
