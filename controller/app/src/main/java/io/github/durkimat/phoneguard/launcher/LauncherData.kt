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
        return pkgs.map { MenuItem(it, if (it == MESSAGES) "Messages" else try { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() } catch (_: Exception) { it }) }
            .sortedWith(compareBy({ val i = preferred.indexOf(it.pkg); if (i < 0) Int.MAX_VALUE else i }, { it.label.lowercase() })) +
            listOf(MenuItem(CALLLOG_PKG, "Call log"), MenuItem(CALC_PKG, "Calculator"), MenuItem(SETTINGS_PKG, "Settings"))
    }

    /** All contacts with a phone number, sorted by name. Empty if the permission is missing. */
    fun contacts(c: Context): List<Contact> = try {
        val byName = LinkedHashMap<String, MutableList<String>>()
        c.contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC",
        )?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(0)?.trim().orEmpty(); val num = cur.getString(1)?.trim().orEmpty()
                if (name.isNotEmpty() && num.isNotEmpty()) { val l = byName.getOrPut(name) { mutableListOf() }; if (num !in l) l.add(num) }
            }
        }
        byName.map { Contact(it.key, it.value) }
    } catch (_: Exception) { emptyList() }

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
            softRight = "Contacts",
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

    /** The most recent calls, newest first, named from contacts where we can. */
    fun calls(c: Context, contacts: List<Contact>): List<CallEntry> {
        val names = HashMap<String, String>()
        for (ct in contacts) for (n in ct.numbers) names.putIfAbsent(key(n), ct.name)
        val out = ArrayList<CallEntry>()
        try {
            c.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null, null, CallLog.Calls.DATE + " DESC LIMIT 50",
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val number = cur.getString(0).orEmpty()
                    val type = when (cur.getInt(2)) { CallLog.Calls.OUTGOING_TYPE -> 2; CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> 3; else -> 1 }
                    val name = names[key(number)] ?: cur.getString(1)?.takeIf { it.isNotBlank() } ?: number.ifBlank { "Unknown" }
                    out.add(CallEntry(number, name, type, cur.getLong(3)))
                }
            }
        } catch (_: Exception) { }
        // test only, as with messages: a debuggable build can read made-up calls
        if (c.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) try {
            val arr = org.json.JSONArray(prefs(c).getString("fakecalls", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val num = o.getString("n")
                out.add(CallEntry(num, names[key(num)] ?: num, o.getInt("t"), o.getLong("d")))
            }
            out.sortByDescending { it.date }
        } catch (e: Exception) { io.github.durkimat.phoneguard.Log.log(c, "calls: fake read failed ${e.javaClass.simpleName} ${e.message}") }
        io.github.durkimat.phoneguard.Log.log(c, "calls: ${out.size} entries")
        return out
    }

    // ---- Messages

    /** Matches numbers written differently (+44 7700... and 07700...) by their last nine digits. */
    private fun key(address: String): String {
        val digits = address.filter { it.isDigit() }
        return if (digits.length >= 7 && address.any { it.isDigit() } && address.count { it.isLetter() } == 0) digits.takeLast(9) else address.lowercase()
    }

    private fun prefs(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Texts we sent ourselves. We are not the default messaging app, so Android does not store them for us. */
    private fun localSent(c: Context): List<Triple<String, String, Long>> = try {
        val arr = org.json.JSONArray(prefs(c).getString("sent", "[]"))
        (0 until arr.length()).map { val o = arr.getJSONObject(it); Triple(o.getString("a"), o.getString("b"), o.getLong("d")) }
    } catch (_: Exception) { emptyList() }

    private fun rememberSent(c: Context, to: String, body: String, at: Long) {
        val list = (localSent(c) + Triple(to, body, at)).takeLast(200)
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("a", it.first).put("b", it.second).put("d", it.third)) }
        prefs(c).edit().putString("sent", arr.toString()).apply()
    }

    /** All conversations, newest first. Reads the phone's texts plus the ones we sent. */
    fun convos(c: Context, contacts: List<Contact>): List<Convo> {
        class Raw(val address: String, val msg: Msg, val unread: Boolean)
        val raws = ArrayList<Raw>()
        try {
            c.contentResolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf("address", "body", "date", "type", "read"), null, null, "date DESC LIMIT 400",
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val addr = cur.getString(0) ?: continue
                    val type = cur.getInt(3)
                    raws.add(Raw(addr, Msg(cur.getString(1).orEmpty(), cur.getLong(2), type != 1), type == 1 && cur.getInt(4) == 0))
                }
            }
        } catch (_: Exception) { }
        // test only: a debuggable build can read made-up messages (the SMS store is write-protected without a SIM and default-app role)
        if (c.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) try {
            val arr = org.json.JSONArray(prefs(c).getString("fake", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                raws.add(Raw(o.getString("a"), Msg(o.getString("b"), o.getLong("d"), o.optBoolean("m")), o.optBoolean("u")))
            }
        } catch (_: Exception) { }
        val have = raws.filter { it.msg.mine }
        for ((to, body, at) in localSent(c)) {
            if (have.none { key(it.address) == key(to) && it.msg.body == body && Math.abs(it.msg.date - at) < 120_000 }) raws.add(Raw(to, Msg(body, at, true), false))
        }
        val names = HashMap<String, String>()
        for (ct in contacts) for (n in ct.numbers) names.putIfAbsent(key(n), ct.name)
        return raws.groupBy { key(it.address) }.map { (k, list) ->
            val sorted = list.sortedBy { it.msg.date }
            Convo(sorted.last().address, names[k] ?: sorted.last().address, sorted.map { it.msg }, list.count { it.unread })
        }.sortedByDescending { it.msgs.last().date }
    }

    /** Sends a text. Returns what to tell the user. Nothing is recorded when it could not be sent. */
    fun sendSms(c: Context, to: String, body: String): String {
        return try {
            val tm = c.getSystemService(TelephonyManager::class.java)
            if (tm.simState != TelephonyManager.SIM_STATE_READY) return "No SIM: not sent"
            val sms = c.getSystemService(android.telephony.SmsManager::class.java)
            sms.sendMultipartTextMessage(to, null, sms.divideMessage(body), null, null)
            rememberSent(c, to, body, Clock.now())
            "Message sent"
        } catch (e: Exception) {
            io.github.durkimat.phoneguard.Log.log(c, "sms: send failed ${e.javaClass.simpleName}")
            "Not sent"
        }
    }
}
