package io.github.durkimat.phoneguard.launcher

import android.app.Activity
import android.os.Bundle
import android.os.PowerManager
import android.view.KeyEvent
import android.view.WindowManager
import io.github.durkimat.phoneguard.Log

/**
 * The power menu, in the Nokia look: Power off and Restart, for anyone in every mode (a normal phone's long press of the power key). The image's window manager
 * opens it with the action io.github.durkimat.phoneguard.POWER_MENU; where this screen is missing or a second long press comes within six seconds, the stock
 * menu is shown instead, so there is always a way to power off. Needs no network. Goes away as soon as it loses the screen.
 */
class PowerMenuActivity : Activity() {
    private lateinit var skin: PaintedSkin
    private val model = LauncherModel()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // before the first unlock only device-protected storage can be read: the look then falls back to the defaults, but the menu still works
        val look = try { Look.current(this) } catch (e: Exception) { null }
        if (look != null) { model.theme = look.theme; model.font = look.font; model.accent = look.accent }
        skin = if (look?.skin == "mac") MacSkin() else NokiaSkin()
        setContentView(skin.create(this))
        model.openPowerMenu()
        draw()
    }

    override fun onNewIntent(i: android.content.Intent) { super.onNewIntent(i); model.openPowerMenu(); draw() }

    override fun onStop() { super.onStop(); finish() }

    private fun draw() {
        val home = try { LauncherData.info(this) } catch (e: Exception) { fallback() }
        skin.render(model, home)
    }

    private fun fallback() = HomeInfo(time = "", date = "", modeLine = "", badges = emptyList(), batteryPct = 100, charging = false, signalBars = 0, softLeft = "", softRight = "")

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (ev.keyCode == KeyEvent.KEYCODE_POWER) return super.dispatchKeyEvent(ev) // the power key keeps its own job (screen off)
        val k = KeyMap.of(ev.keyCode) ?: return super.dispatchKeyEvent(ev)
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) perform(model.onKey(k.first, k.second))
        return true
    }

    private fun perform(a: Action) {
        when (a) {
            Action.PowerOff -> { Log.log(this, "power menu: power off"); powerOff() }
            Action.PowerRestart -> { Log.log(this, "power menu: restart"); restart() }
            Action.PowerClose -> finish()
            else -> draw()
        }
    }

    private fun restart() {
        try { getSystemService(PowerManager::class.java).reboot(null) } catch (e: Exception) { Log.log(this, "power menu: restart failed ${e.javaClass.simpleName}"); finish() }
    }

    /** PowerManager.shutdown is hidden, so it is called by name; the REBOOT permission covers it. */
    private fun powerOff() {
        try {
            PowerManager::class.java.getMethod("shutdown", Boolean::class.javaPrimitiveType, String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(getSystemService(PowerManager::class.java), false, null, false)
        } catch (e: Exception) { Log.log(this, "power menu: power off failed ${e.javaClass.simpleName}"); finish() }
    }
}
