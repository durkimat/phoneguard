package io.github.durkimat.phoneguard.net

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import io.github.durkimat.phoneguard.AlarmReceiver
import io.github.durkimat.phoneguard.Clock
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.Store
import io.github.durkimat.phoneguard.core.Engine
import io.github.durkimat.phoneguard.core.RadioInputs
import io.github.durkimat.phoneguard.core.RadioIntent
import io.github.durkimat.phoneguard.core.RadioPolicy
import io.github.durkimat.phoneguard.core.RadioWanted
import io.github.durkimat.phoneguard.incall.CallState
import io.github.durkimat.phoneguard.music.Radio
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wi-Fi, Bluetooth and mobile data off while the phone is locked and unused, back on at unlock (as the child had them) and for short
 * check-in windows (the HA sync, a position report, a WhatsApp check). The decision itself is [RadioPolicy]; this class gathers what it
 * needs from the phone and applies the answer. See docs/radios-per-mode.md.
 */
object RadioManager {
    private const val WINDOW_MS = 90_000L
    private const val TAIL_MS = 5_000L // a window stays open a moment after the last job ends
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "radio-manager") }
    private val jobs = AtomicInteger(0)
    @Volatile private var windowStart = 0L
    @Volatile private var windowUntil = 0L
    @Volatile private var screenOffAt = 0L
    private val holds = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val cmdAt = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>()
    private val bootAt = SystemClock.elapsedRealtime()

    private fun prefs(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("radios", Context.MODE_PRIVATE)

    // ---- what the child wants ------------------------------------------------------------------

    /** What the child wants. The first time we look, it is simply how the radios are now (so nothing the child had switched off is switched on). */
    fun intent(c: Context): RadioIntent {
        val p = prefs(c)
        if (!p.contains("seeded")) p.edit().putBoolean("wifi", WifiControl.enabled(c)).putBoolean("bt", BluetoothControl.enabled(c)).putBoolean("data", dataOn(c)).putBoolean("seeded", true).apply()
        return RadioIntent(p.getBoolean("wifi", true), p.getBoolean("bt", true), p.getBoolean("data", true))
    }

    private fun saveIntent(c: Context, radio: String, on: Boolean) { prefs(c).edit().putBoolean(radio, on).apply() }

    /** The child switched a radio in our screens (or Android reported a change we did not make): remember it, and for our screens act on it now. */
    fun userSet(c: Context, radio: String, on: Boolean) {
        saveIntent(c, radio, on)
        worker.execute { apply(c.applicationContext, radio, on, "child") ; evaluateNow(c.applicationContext, "child switched $radio") }
    }

    /** Android told us a radio changed state; if we did not just do it ourselves, it was the child (in Android's settings): that is the new intent. */
    fun onExternalState(c: Context, radio: String, on: Boolean) {
        val mine = cmdAt[radio]
        if (mine != null && mine.first == on && System.currentTimeMillis() - mine.second < 15_000) return
        if (intent(c).let { if (radio == "wifi") it.wifi else if (radio == "bt") it.bt else it.data } != on) {
            saveIntent(c, radio, on); Log.log(c, "radios: $radio switched ${if (on) "on" else "off"} by the child")
        }
    }

    // ---- what the phone is doing ---------------------------------------------------------------

    fun onScreenOff(c: Context) { screenOffAt = System.currentTimeMillis(); evaluate(c, "screen off") }
    fun onScreenOn(c: Context) { evaluate(c, "screen on") }
    fun onUnlock(c: Context) { screenOffAt = 0; evaluate(c, "unlock") }

    /** Keep the radios on for this long because something needs them (a download, an update). */
    fun holdFor(c: Context, key: String, ms: Long) { holds[key] = System.currentTimeMillis() + ms; evaluate(c, "hold $key") }

    /** A job that needs the network starts: open (or extend) a check-in window. Call [end] when it is done. */
    fun begin(c: Context, why: String) {
        val now = System.currentTimeMillis()
        jobs.incrementAndGet()
        if (now >= windowUntil) windowStart = now
        windowUntil = maxOf(windowUntil, now + WINDOW_MS)
        evaluate(c, "window: $why")
    }

    fun end(c: Context, why: String) {
        if (jobs.decrementAndGet() <= 0) { jobs.set(0); windowUntil = minOf(windowUntil, System.currentTimeMillis() + TAIL_MS); evaluate(c, "window done: $why") }
    }

    /** Waits (off the main thread) until some network is up, for a job that has just opened a window. True if there is one. */
    fun awaitNetwork(c: Context, timeoutMs: Long): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (try { cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true } catch (_: Exception) { false }) return true
            if (SystemClock.elapsedRealtime() >= until) return false
            try { Thread.sleep(500) } catch (_: InterruptedException) { return false }
        }
    }

    fun dataSupported(c: Context): Boolean = try { c.getSystemService(TelephonyManager::class.java).simState == TelephonyManager.SIM_STATE_READY } catch (_: Exception) { false }

    private fun dataOn(c: Context) = try { Settings.Global.getInt(c.contentResolver, "mobile_data", 1) == 1 } catch (_: Exception) { true }

    private fun inputs(c: Context, now: Long): RadioInputs {
        val cfg = Store.config(c)
        val eff = Engine.effective(cfg, Store.boost(c), Clock.now())
        val pm = c.getSystemService(PowerManager::class.java)
        val kg = c.getSystemService(KeyguardManager::class.java)
        val inUse = pm.isInteractive && !kg.isKeyguardLocked
        val live = eff.location != "off" && eff.locEvery == 0
        val bat = c.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val charging = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val call = CallState.info != null || try { c.getSystemService(TelephonyManager::class.java).callState != TelephonyManager.CALL_STATE_IDLE } catch (_: Exception) { false }
        val music = Radio.playing || try { c.getSystemService(android.media.AudioManager::class.java).isMusicActive } catch (_: Exception) { false }
        holds.entries.removeIf { it.value < now }
        val hold = charging || call || music || holds.isNotEmpty() || SystemClock.elapsedRealtime() - bootAt < 60_000L
        val grace = maxOf(1, Engine.modeDef(cfg, eff.modeId).screenOffMin) * 60_000L
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val wifiConnected = try { cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true } catch (_: Exception) { false }
        val off = if (inUse) 0L else if (screenOffAt == 0L) grace else now - screenOffAt
        return RadioInputs(
            sleepEnabled = eff.radiosSleep && !live, inUse = inUse, offForMs = off, graceMs = grace, hold = hold,
            windowOpen = now < windowUntil, windowAgeMs = now - windowStart, wifiConnected = wifiConnected,
            locationReporting = eff.location != "off", dataSupported = dataSupported(c), intent = intent(c),
        )
    }

    // ---- deciding and applying -----------------------------------------------------------------

    fun evaluate(c: Context, why: String) { val app = c.applicationContext; worker.execute { try { evaluateNow(app, why) } catch (e: Exception) { Log.log(app, "radios: ${e.javaClass.simpleName} ${e.message}") } } }

    private var lastLine = ""

    private fun evaluateNow(c: Context, why: String) {
        val now = System.currentTimeMillis()
        val i = inputs(c, now)
        val want = RadioPolicy.decide(i)
        val dry = Store.config(c)?.idleDryRun == true
        val wifi = WifiControl.enabled(c); val bt = BluetoothControl.enabled(c); val data = dataOn(c)
        val dataNow = if (i.dataSupported) data else want.data // without a SIM data is not ours to change, so it never counts as a difference
        val line = "radios: wifi ${if (wifi) "on" else "off"}->${if (want.wifi) "on" else "off"} bt ${if (bt) "on" else "off"}->${if (want.bt) "on" else "off"} data ${if (data) "on" else "off"}->${if (want.data) "on" else "off"}" +
            " ($why; sleeping=${RadioPolicy.sleeping(i)} window=${i.windowOpen} hold=${i.hold} inUse=${i.inUse}${if (dry) ", dry run" else ""})"
        if (wifi != want.wifi || bt != want.bt || dataNow != want.data) { if (line != lastLine) Log.log(c, line); lastLine = line }
        if (!dry) {
            if (wifi != want.wifi) apply(c, "wifi", want.wifi, why)
            if (bt != want.bt) apply(c, "bt", want.bt, why)
            if (data != want.data && i.dataSupported) apply(c, "data", want.data, why)
        }
        scheduleNext(c, i, now)
    }

    private fun apply(c: Context, radio: String, on: Boolean, why: String) {
        cmdAt[radio] = on to System.currentTimeMillis()
        when (radio) {
            "wifi" -> WifiControl.setEnabled(c, on)
            "bt" -> BluetoothControl.setEnabled(c, on)
            "data" -> try { Settings.Global.putInt(c.contentResolver, "mobile_data", if (on) 1 else 0) } catch (e: Exception) { Log.log(c, "radios: mobile data: ${e.javaClass.simpleName}") }
        }
    }

    /** One alarm for whichever comes first: the end of the grace period, the Wi-Fi wait inside a window, or the end of the window. */
    private fun scheduleNext(c: Context, i: RadioInputs, now: Long) {
        var after: Long? = RadioPolicy.nextCheckMs(i)
        if (i.windowOpen) after = minOf(after ?: Long.MAX_VALUE, windowUntil - now + 500)
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, 9, Intent(c, AlarmReceiver::class.java).putExtra("kind", "radio"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (after == null) { am.cancel(pi); return }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + maxOf(after, 1_000L), pi)
    }

    /** What to show for a diary or a test: the last change and why. */
    fun lastChange(): String = lastLine

    /** Registered once by the guard service: Android's own radio changes and the things that may start or stop a hold. */
    val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION -> when (i.getIntExtra(android.net.wifi.WifiManager.EXTRA_WIFI_STATE, -1)) {
                    android.net.wifi.WifiManager.WIFI_STATE_ENABLED -> onExternalState(c, "wifi", true)
                    android.net.wifi.WifiManager.WIFI_STATE_DISABLED -> onExternalState(c, "wifi", false)
                }
                android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED -> when (i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                    android.bluetooth.BluetoothAdapter.STATE_ON -> onExternalState(c, "bt", true)
                    android.bluetooth.BluetoothAdapter.STATE_OFF -> onExternalState(c, "bt", false)
                }
                else -> evaluate(c, (i.action ?: "?").substringAfterLast('.').lowercase())
            }
        }
    }
}
