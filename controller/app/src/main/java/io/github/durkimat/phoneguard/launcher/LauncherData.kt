package io.github.durkimat.phoneguard.launcher

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.CallLog
import android.provider.Telephony
import android.telephony.TelephonyManager
import io.github.durkimat.phoneguard.Clock
import io.github.durkimat.phoneguard.Enforcer
import io.github.durkimat.phoneguard.Store
import io.github.durkimat.phoneguard.core.Engine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Reads the phone and the current mode into what the launcher shows. All reads are safe to fail. */
object LauncherData {
    const val DIALER = "com.android.dialer"
    const val MESSAGES = "com.android.messaging"
    const val CONTACTS = "com.android.contacts"
    const val WHATSAPP = "com.whatsapp"

    /** Never listed in this launcher: system plumbing and the controller itself. */
    private val notInMenu = setOf(
        "com.android.settings", "com.android.documentsui", "com.google.android.gms", "com.google.android.gsf",
        "com.android.vending", "io.github.sspanak.tt9", "com.android.launcher3", "com.android.systemui",
        "io.homeassistant.companion.android", "io.homeassistant.companion.android.minimal",
        "eu.dumbdroid.deviceowner", "eu.dumbdroid.updater", "eu.dumbdroid.settingspanel", "me.phh.treble.app",
        "org.lineageos.lineageparts", "com.android.permissioncontroller", "com.android.packageinstaller",
    )

    /** Menu order: calls and messages first, the way a classic phone puts them. */
    private val preferred = listOf(DIALER, MESSAGES, WHATSAPP, CONTACTS)

    fun items(c: Context): List<MenuItem> {
        val pm = c.packageManager
        val pkgs = Enforcer.launcherPackages(c) - notInMenu - c.packageName
        return pkgs.map { MenuItem(it, try { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() } catch (_: Exception) { it }) }
            .sortedWith(compareBy({ val i = preferred.indexOf(it.pkg); if (i < 0) Int.MAX_VALUE else i }, { it.label.lowercase() })) +
            MenuItem(SETTINGS_PKG, "Settings")
    }

    fun contactsPackage(c: Context): String? =
        if (Enforcer.launcherPackages(c).contains(CONTACTS)) CONTACTS else null

    fun info(c: Context): HomeInfo {
        val now = Clock.now()
        val cfg = Store.config(c)
        val boost = Store.boost(c)
        val eff = Engine.effective(cfg, boost, now)
        val name = Engine.modeDef(cfg, eff.modeId).name
        val change = Engine.nextChange(cfg, boost, now)
        val sameDay = SimpleDateFormat("yyyyMMdd", Locale.UK)
        val until = change?.let {
            val d = Date(it.first)
            if (sameDay.format(d) == sameDay.format(Date(now))) SimpleDateFormat("HH:mm", Locale.UK).format(d)
            else SimpleDateFormat("EEE HH:mm", Locale.UK).format(d)
        }
        val date = Date(now)
        val bat = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return HomeInfo(
            time = SimpleDateFormat("HH:mm", Locale.UK).format(date),
            date = SimpleDateFormat("EEE d MMM", Locale.UK).format(date),
            modeLine = if (cfg?.override != null && cfg.modes.containsKey(cfg.override)) name else if (until != null) "$name until $until" else name,
            missedCalls = missedCalls(c),
            newMessages = newMessages(c),
            batteryPct = if (level >= 0) level * 100 / scale else 0,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            signalBars = signal(c),
            softLeft = "Menu",
            softRight = if (contactsPackage(c) != null) "Contacts" else "",
        )
    }

    private fun missedCalls(c: Context): Int = try {
        c.contentResolver.query(
            CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID),
            "${CallLog.Calls.TYPE}=? AND ${CallLog.Calls.NEW}=1", arrayOf(CallLog.Calls.MISSED_TYPE.toString()), null,
        )?.use { it.count } ?: 0
    } catch (_: Exception) { 0 }

    private fun newMessages(c: Context): Int = try {
        c.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id"), "read=0", null, null)?.use { it.count } ?: 0
    } catch (_: Exception) { 0 }

    private fun signal(c: Context): Int = try {
        val tm = c.getSystemService(TelephonyManager::class.java)
        if (tm.simState != TelephonyManager.SIM_STATE_READY) -1 else (tm.signalStrength?.level ?: 0)
    } catch (_: Exception) { -1 }
}
