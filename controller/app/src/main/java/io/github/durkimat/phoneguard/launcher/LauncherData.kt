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
        "org.lineageos.aperture", // the old camera: our own themed camera replaces it (it stays installed for other apps that ask for a camera)
    )

    /** Menu order: calls and messages first, the way a classic phone puts them. */
    private val preferred = listOf(DIALER, MESSAGES, CONTACTS)

    /** True when the real WhatsApp app is what the menus use in the mode in force (see [Engine.useRealWhatsApp]). */
    fun useRealWhatsApp(c: Context): Boolean {
        val eff = Engine.effective(Store.config(c), Store.boost(c), Clock.now())
        return Engine.useRealWhatsApp(eff.waApp, Enforcer.launcherPackages(c).contains(WHATSAPP), io.github.durkimat.phoneguard.whatsapp.WhatsApp.linked(c))
    }

    /** Every app the menu can start, in menu order. The kiosk lock allows exactly these. */
    /** True when the mode in force lets the touchscreen work: Android apps are made for touch, so they are only offered then. */
    fun touchAllowed(c: Context): Boolean = Engine.effective(Store.config(c), Store.boost(c), Clock.now()).lockdown.touch

    /** Music is offered wherever the phone is not locked to its apps (School); a per-mode setting can follow. */
    fun musicShown(c: Context): Boolean = !Engine.effective(Store.config(c), Store.boost(c), Clock.now()).lockdown.kiosk

    /** True when the mode in force lists notifications on the Nokia screens. */
    fun notesShown(c: Context): Boolean = Engine.effective(Store.config(c), Store.boost(c), Clock.now()).notes == "show"

    /** True when the mode in force offers the themed camera. */
    fun cameraAllowed(c: Context): Boolean = Engine.effective(Store.config(c), Store.boost(c), Clock.now()).camera
    /** Whether the microphone button shows in text fields: the mode allows voice typing (the synced config) and the phone is paired to the Worker. */
    fun voiceCommandsAllowed(c: Context): Boolean = io.github.durkimat.phoneguard.companion.WorkerLink.isPaired(c) && Engine.voice(Store.config(c), Clock.now()).second
    fun voiceTypingAllowed(c: Context): Boolean = io.github.durkimat.phoneguard.companion.WorkerLink.isPaired(c) && Engine.voice(Store.config(c), Clock.now()).first

    @Volatile private var nextCache: Pair<Long, List<NextEvent>> = 0L to emptyList()
    /** A calendar change was seen: the next lookup reads the calendar again instead of using the minute's answer. */
    fun invalidateNext() { nextCache = 0L to emptyList() }
    /** How many days ahead the strip looks for its two events (today included). */
    private const val NEXT_DAYS = 7
    /**
     * The next two timed events that have not ended, looked for today and the following days: "15:00 Dentist" for today, "20/11 09:00 Football" for a later
     * day. All-day events are left out. Looked up at most once a minute (a calendar change clears it).
     */
    private fun nextUp(c: Context, now: Long): List<NextEvent> {
        if (now - nextCache.first < 60_000L && now >= nextCache.first) return nextCache.second
        val text = try {
            val z = java.time.ZoneId.systemDefault(); val today = java.time.Instant.ofEpochMilli(now).atZone(z).toLocalDate()
            val ev = io.github.durkimat.phoneguard.calendar.CompositeCalendar(c).events(now, io.github.durkimat.phoneguard.calendar.Cal.dayStart(today.plusDays(NEXT_DAYS.toLong()), z))
            io.github.durkimat.phoneguard.calendar.Cal.agenda(ev, today, NEXT_DAYS, z).filter { !it.event.allDay && it.event.end > now }
                .distinctBy { it.event.start to it.event.title }.take(2).map { it.line(today, z) }
        } catch (_: Exception) { emptyList() }
        nextCache = now to text
        return text
    }

    private fun io.github.durkimat.phoneguard.calendar.AgendaItem.line(today: java.time.LocalDate, z: java.time.ZoneId): NextEvent {
        val time = io.github.durkimat.phoneguard.calendar.Cal.clock(event.start, z)
        val date = if (day == today) "" else "%02d/%02d ".format(day.dayOfMonth, day.monthValue)
        return NextEvent(date + time + " ", event.title.ifBlank { "(no title)" })
    }

    fun items(c: Context): List<MenuItem> {
        val pm = c.packageManager
        // our own WhatsApp screens are in Messages: the real app is only offered in the menu when it is the one in use
        val pkgs = Enforcer.launcherPackages(c) - notInMenu - c.packageName - (if (useRealWhatsApp(c)) emptySet() else setOf(WHATSAPP))
        return pkgs.map { MenuItem(it, if (it == MESSAGES) "Messages" else try { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() } catch (_: Exception) { it }) }
            .sortedWith(compareBy({ val i = preferred.indexOf(it.pkg); if (i < 0) Int.MAX_VALUE else i }, { it.label.lowercase() })) +
            (if (musicShown(c)) listOf(MenuItem(MUSIC_PKG, "Music")) else emptyList()) + (if (whereShown(c)) listOf(MenuItem(WHERE_PKG, "Navigate")) else emptyList()) + (if (notesShown(c)) listOf(MenuItem(NOTES_PKG, "Notifications")) else emptyList()) + (if (musicShown(c)) listOf(MenuItem(RUNNING_PKG, "Running Apps")) else emptyList()) + (if (cameraAllowed(c)) listOf(MenuItem(CAMERA_PKG, "Camera")) else emptyList()) + (if (WeatherStore.hasLocation(c)) listOf(MenuItem(WEATHER_PKG, "Weather")) else emptyList()) + (if (musicShown(c)) listOf(MenuItem(MAIL_PKG, "Email")) else emptyList()) + listOf(MenuItem(CALENDAR_PKG, "Calendar"), MenuItem(GAMES_PKG, "Classic Games"), MenuItem(CALLLOG_PKG, "Call log"), MenuItem(CALC_PKG, "Calculator"), MenuItem(SETTINGS_PKG, "Settings"), MenuItem(CLOCK_PKG, "Clock"))
    }

    /** What the main menu lists: the phone basics, the apps a parent pinned, "Android Apps" if any are left and the mode allows touch, then the built-ins. */
    /** "Navigate" is offered when the phone is paired to the Worker (which does the place and journey lookups), the mode is not locked to one app and the mode allows it (`whereTo` in the synced config, absent = on). It sits after Classic Games so the digit keys of the other items stay where they were. */
    fun whereShown(c: Context): Boolean = musicShown(c) && io.github.durkimat.phoneguard.companion.WorkerLink.isPaired(c) && Engine.whereTo(Store.config(c), Clock.now())

    private val builtIn = setOf(EXTRAS_PKG, WHERE_PKG, MAIL_PKG, CALENDAR_PKG, MUSIC_PKG, NOTES_PKG, RUNNING_PKG, CAMERA_PKG, WEATHER_PKG, GAMES_PKG, CALLLOG_PKG, CALC_PKG, SETTINGS_PKG, CLOCK_PKG)

    /**
     * The main menu (a 3 x 3 grid, nine at most), in the order the numbers 1 to 9 reach: Phone, Messages, Contacts (where the mode allows those apps), Calendar,
     * Clock, Extras, Settings, then Camera and Music, the two a mode can hide, last. Everything else is inside Extras, Messages, Music or Settings (see [extrasMenu]).
     */
    fun menu(all: List<MenuItem>, pinned: List<String>, androidApps: Boolean = true): List<MenuItem> {
        val apps = all.filter { it.pkg !in builtIn }
        val core = preferred.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }
        fun one(pkg: String) = all.filter { it.pkg == pkg }
        return core + one(CALENDAR_PKG) + one(CLOCK_PKG) + listOf(MenuItem(EXTRAS_PKG, "Extras")) + one(SETTINGS_PKG) + one(CAMERA_PKG) + one(MUSIC_PKG)
    }

    /** What the Extras screen lists: Weather, Classic Games, Calculator, Navigate, the apps a parent pinned, then Android Apps (only where touch works and there are apps left). */
    fun extrasMenu(all: List<MenuItem>, pinned: List<String>, androidApps: Boolean = true): List<MenuItem> {
        val apps = all.filter { it.pkg !in builtIn }
        val pins = pinned.mapNotNull { p -> apps.firstOrNull { it.pkg == p && it.pkg !in preferred } }
        val others = apps - preferred.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }.toSet() - pins.toSet()
        fun one(pkg: String) = all.filter { it.pkg == pkg }
        return one(WEATHER_PKG) + one(GAMES_PKG) + one(CALC_PKG) + one(WHERE_PKG) + pins + (if (others.isEmpty() || !androidApps) emptyList() else listOf(MenuItem(APPS_PKG, "Android Apps")))
    }

    /** The built-in entries that live inside other screens (Messages: Notices and Email; Settings: Running Apps; the Phone screen: Call log), where the mode shows them. */
    fun settingsExtras(all: List<MenuItem>): List<MenuItem> = listOf(NOTES_PKG, RUNNING_PKG, MAIL_PKG, CALLLOG_PKG).mapNotNull { p -> all.firstOrNull { it.pkg == p } }

    /** The allowed apps that are not in the main menu, for the "Android Apps" list. */
    fun otherApps(all: List<MenuItem>, pinned: List<String>): List<MenuItem> {
        return all.filter { it.pkg !in builtIn && it.pkg !in preferred && it.pkg !in pinned }
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

    /** For tests on a real phone: shows these symbols instead of the real counts (set by the debug build only). */
    @Volatile var debugBadges: List<Badge>? = null

    /** The battery page: the level now, a rough time left from the diary, today's screen time and the top apps by minutes. */
    fun batteryInfo(c: Context): BatteryInfo {
        val bat = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val charging = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) > 0
        val pts = io.github.durkimat.phoneguard.PowerLog.recent(c)
        val rate = io.github.durkimat.phoneguard.PowerLog.rate(pts)
        val remaining = pts.lastOrNull()?.uah?.div(1000.0)
        val left = if (!charging && rate != null && rate > 1.0 && remaining != null && remaining > 0) remaining / rate else null
        var total = 0
        val top = ArrayList<Pair<String, Int>>()
        try {
            val today = io.github.durkimat.phoneguard.Usage.recent(c)?.let { u -> u.optJSONObject(u.keys().asSequence().sorted().last()) }
            if (today != null) {
                val pm = c.packageManager
                val list = today.keys().asSequence().map { it to today.getInt(it) }.filter { it.first != c.packageName && it.first != "com.android.launcher3" }.sortedByDescending { it.second }.toList()
                total = list.sumOf { it.second } / 60
                list.take(3).forEach { (pkg, sec) -> top.add((try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }) to sec / 60) }
            }
        } catch (_: Exception) { }
        return BatteryInfo(level * 100 / scale, charging, left, total, top)
    }

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
            badges = listOf(
                Badge(BadgeKind.SMS, newMessages(c)),
                Badge(BadgeKind.WHATSAPP, if (io.github.durkimat.phoneguard.whatsapp.WhatsApp.linked(c) && !useRealWhatsApp(c)) io.github.durkimat.phoneguard.whatsapp.WhatsApp.store(c).unreadTotal() else 0),
                Badge(BadgeKind.MISSED, missedCalls(c)),
                Badge(BadgeKind.NOTES, io.github.durkimat.phoneguard.notes.Notes.count()),
                Badge(BadgeKind.MAIL, if (musicShown(c)) io.github.durkimat.phoneguard.mail.Mail.unread(c) else 0),
            ).filter { it.count > 0 }.let { debugBadges ?: it },
            batteryPct = if (level >= 0) level * 100 / scale else 0,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            signalBars = signal(c),
            softLeft = "Menu",
            softRight = "Contacts",
            nextEvents = if (eff.nowStrip) nextUp(c, now) else emptyList(),
            musicOn = musicShown(c) && musicPlaying(c),
            wifi = wifiState(c),
            bt = bluetoothState(c),
            data = dataState(c),
            dataView = if (eff.dataWidget) try { io.github.durkimat.phoneguard.net.DataUsage.view(c, now) } catch (_: Exception) { null } else null,
            alarmSet = try {
                val st = io.github.durkimat.phoneguard.alarm.AlarmStore
                Engine.alarms(cfg, now) && (st.alarms(c).any { it.on } || st.snoozeAt(c) > now)
            } catch (_: Exception) { false },
            timerEndAt = try { io.github.durkimat.phoneguard.alarm.AlarmStore.timer(c).let { if (it.running) it.endAt else 0L } } catch (_: Exception) { 0L },
            alarmText = try {
                val st = io.github.durkimat.phoneguard.alarm.AlarmStore
                val next = if (Engine.alarms(cfg, now)) listOfNotNull(io.github.durkimat.phoneguard.alarm.AlarmMath.nextFrom(st.alarms(c), now, java.time.ZoneId.systemDefault()), st.snoozeAt(c).takeIf { it > now }).minOrNull() else null
                if (next == null) "" else java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(java.util.Date(next))
            } catch (_: Exception) { "" },
        )
    }

    /** Bluetooth for the status line: not shown when it is off or the mode is locked down; faint when on with nothing connected. */
    internal fun bluetoothState(c: Context): Int = try {
        if (Engine.effective(Store.config(c), Store.boost(c), Clock.now()).lockdown.kiosk) -1
        else {
            val a = c.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            when {
                a == null -> -1
                !a.isEnabled -> 2
                a.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP) == android.bluetooth.BluetoothProfile.STATE_CONNECTED ||
                    a.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET) == android.bluetooth.BluetoothProfile.STATE_CONNECTED -> 1
                else -> 0
            }
        }
    } catch (_: Exception) { -1 }

    /** Mobile data for the status line: only on a phone with a SIM, and not in the locked-down mode; 2 when switched off, 1 when it is the connection in use. */
    internal fun dataState(c: Context): Int = try {
        if (Engine.effective(Store.config(c), Store.boost(c), Clock.now()).lockdown.kiosk || !io.github.durkimat.phoneguard.net.RadioManager.dataSupported(c)) -1
        else if (!io.github.durkimat.phoneguard.net.RadioManager.dataOn(c)) 2
        else { val cm = c.getSystemService(android.net.ConnectivityManager::class.java); if (cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true) 1 else 0 }
    } catch (_: Exception) { -1 }

    /** Shown in every mode but the locked-down one (School), greyed out while there is no Wi-Fi connection. */
    internal fun wifiState(c: Context): Int = try {
        if (Engine.effective(Store.config(c), Store.boost(c), Clock.now()).lockdown.kiosk) -1
        else {
            val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
            if (!io.github.durkimat.phoneguard.net.WifiControl.enabled(c)) 2
            else if (cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true) 1 else 0
        }
    } catch (_: Exception) { 0 }

    /** Music is playing on this phone (a media session that is playing). */
    private fun musicPlaying(c: Context): Boolean = try { io.github.durkimat.phoneguard.music.Radio.playing || io.github.durkimat.phoneguard.music.MusicController.info(c).playing } catch (_: Exception) { false }

    private fun missedCalls(c: Context): Int = try {
        c.contentResolver.query(
            CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID),
            "${CallLog.Calls.TYPE}=? AND ${CallLog.Calls.NEW}=1", arrayOf(CallLog.Calls.MISSED_TYPE.toString()), null,
        )?.use { it.count } ?: 0
    } catch (_: Exception) { 0 }

    private fun newMessages(c: Context): Int = try {
        c.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("address", "date"), "read=0", null, null)?.use { cur ->
            var n = 0
            while (cur.moveToNext()) if (cur.getLong(1) > seenSms(c, cur.getString(0).orEmpty())) n++
            n
        } ?: 0
    } catch (_: Exception) { 0 }

    /** The date of the newest text of this conversation that was opened here; texts up to then are read, whatever the phone's own flag says. */
    private fun seenSms(c: Context, address: String): Long = prefs(c).getLong("sms_seen_" + key(address), 0L)

    /** Opening a conversation marks it read. The phone's own flag is also set when the system allows it, and its notification is cleared. */
    fun markSmsSeen(c: Context, address: String) {
        val k = key(address)
        var newest = 0L
        try {
            c.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("address", "date"), null, null, "date DESC LIMIT 400")?.use { cur ->
                while (cur.moveToNext()) if (key(cur.getString(0).orEmpty()) == k) newest = maxOf(newest, cur.getLong(1))
            }
        } catch (_: Exception) { }
        newest = maxOf(newest, Clock.now())
        prefs(c).edit().putLong("sms_seen_$k", newest).apply()
        try {
            val v = android.content.ContentValues().apply { put("read", 1); put("seen", 1) }
            c.contentResolver.update(Telephony.Sms.Inbox.CONTENT_URI, v, "address=? AND read=0", arrayOf(address))
        } catch (_: Exception) { } // write-protected without the default-app role: the local mark above is what counts
        io.github.durkimat.phoneguard.notes.NoteService.instance?.clearMessaging()
    }

    internal fun signal(c: Context): Int = try {
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
                    raws.add(Raw(addr, Msg(cur.getString(1).orEmpty(), cur.getLong(2), type != 1), type == 1 && cur.getInt(4) == 0 && cur.getLong(2) > seenSms(c, addr)))
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
