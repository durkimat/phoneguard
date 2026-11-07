package io.github.durkimat.phoneguard.lab

import android.Manifest
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.concurrent.thread

/**
 * Throwaway lab UI. One button per question the spec still has open.
 * Everything logs to the on-screen log and to lab.log (adb pull).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var appSpinner: Spinner
    private var appList: List<Pair<String, String>> = emptyList() // package, label
    private var grayObserver: ContentObserver? = null
    private val ui = Handler(Looper.getMainLooper())

    private val dpm get() = Dpm.dpm(this)
    private val admin get() = Dpm.admin(this)

    private val restrictions = listOf(
        "Factory reset" to UserManager.DISALLOW_FACTORY_RESET,
        "Safe boot" to UserManager.DISALLOW_SAFE_BOOT,
        "Debugging features (auto-reverts in 3 min)" to UserManager.DISALLOW_DEBUGGING_FEATURES,
        "Config date/time" to UserManager.DISALLOW_CONFIG_DATE_TIME,
        "Add user" to UserManager.DISALLOW_ADD_USER,
        "Add managed profile" to UserManager.DISALLOW_ADD_MANAGED_PROFILE,
        "Switch user" to UserManager.DISALLOW_USER_SWITCH,
        "Install unknown sources" to UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
        "Config VPN" to UserManager.DISALLOW_CONFIG_VPN,
        "Config private DNS" to UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
        "USB file transfer" to UserManager.DISALLOW_USB_FILE_TRANSFER,
        "Apps control (force stop / clear data)" to UserManager.DISALLOW_APPS_CONTROL,
        "Modify accounts" to UserManager.DISALLOW_MODIFY_ACCOUNTS,
    )
    private val restrictionBoxes = mutableListOf<Pair<CheckBox, String>>()

    private val scan = registerForActivityResult(ScanContract()) { r ->
        if (r.contents == null) log("QR scan cancelled")
        else log("QR scan OK len=${r.contents.length} text=${r.contents.take(160)}")
        refreshLog()
    }

    private fun log(m: String) = LabLog.log(this, m)

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 40)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        build()
        LabLog.listener = { ui.post { refreshLog() } }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        LabLog.listener = null
        super.onDestroy()
    }

    // ---- UI helpers ----------------------------------------------------------------------

    private fun title(t: String) {
        root.addView(TextView(this).apply {
            text = t; textSize = 16f; setTypeface(null, Typeface.BOLD); setPadding(0, 28, 0, 6)
        })
    }

    private fun note(t: String) {
        root.addView(TextView(this).apply { text = t; textSize = 12f })
    }

    private fun act(name: String, f: () -> Unit) {
        try {
            f()
        } catch (e: Throwable) {
            log("$name FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
        refresh()
    }

    private fun btn(t: String, f: () -> Unit) {
        root.addView(Button(this).apply {
            text = t; isAllCaps = false
            setOnClickListener { act(t, f) }
        })
    }

    private fun input(hint: String, default: String, number: Boolean = false): EditText =
        EditText(this).apply {
            this.hint = hint; setText(default); textSize = 13f
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
            root.addView(this)
        }

    // ---- UI ------------------------------------------------------------------------------

    private fun build() {
        root.addView(TextView(this).apply { text = "Phone Guard Lab (throwaway)"; textSize = 20f })
        note("Lab build, testOnly. Not the real app. Never run on a phone someone relies on.")

        title("Status")
        statusView = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE }
        root.addView(statusView)
        btn("Refresh status + dump restrictions") { dumpRestrictions() }
        btn("RELEASE device owner (safe exit)") {
            dpm.clearDeviceOwnerApp(packageName); log("released device owner")
        }

        title("Restrictions (device owner)")
        note("Tick to apply, untick to clear. Debugging auto-reverts. Settings state is read live.")
        restrictions.forEach { (label, key) ->
            val box = CheckBox(this).apply { text = label; textSize = 13f }
            box.setOnClickListener {
                act("restriction $key") { setRestriction(box, key) }
            }
            root.addView(box)
            restrictionBoxes += box to key
        }
        btn("Clear ALL our restrictions") {
            restrictions.forEach { (_, k) -> Dpm.clear(this, k) }; log("cleared all restrictions")
        }

        title("Hide / suspend an app")
        appSpinner = Spinner(this)
        root.addView(appSpinner)
        btn("Reload app list") { loadApps() }
        btn("Hide (setApplicationHidden)") { hideSelected(true) }
        btn("Unhide") { hideSelected(false) }
        btn("Suspend (setPackagesSuspended)") { suspendSelected(true) }
        btn("Unsuspend") { suspendSelected(false) }
        btn("Report hidden/suspended state of selection") { reportSelected() }
        btn("Unhide everything we hid") {
            Prefs.hidden(this).forEach { p ->
                val ok = dpm.setApplicationHidden(admin, p, false)
                log("unhide $p ok=$ok"); if (ok) Prefs.removeHidden(this, p)
            }
        }
        root.addView(CheckBox(this).apply {
            text = "Quarantine newly installed apps (hide on install, measures delay)"
            textSize = 13f
            isChecked = Prefs.quarantine(this@MainActivity)
            setOnClickListener { Prefs.setQuarantine(this@MainActivity, isChecked); log("quarantine=$isChecked") }
        })
        note("Protected packages are refused (settings, dialer, messaging, keyboard, microG...).")

        title("Grayscale")
        btn("Gray ON (direct secure-settings write)") { setGray(true) }
        btn("Gray OFF") { setGray(false) }
        btn("Try device-owner self-grant of WRITE_SECURE_SETTINGS") {
            val ok = dpm.setPermissionGrantState(
                admin, packageName, Manifest.permission.WRITE_SECURE_SETTINGS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
            log("self-grant WRITE_SECURE_SETTINGS call=$ok now=${granted(Manifest.permission.WRITE_SECURE_SETTINGS)}")
        }
        btn("Start/stop re-assert watcher (while this app is open)") { toggleGrayWatch() }
        note("Adb grant: adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")

        title("Background survival and power")
        btn("Protect from force-stop (setUserControlDisabledPackages)") {
            dpm.setUserControlDisabledPackages(admin, listOf(packageName)); log("protected from user control")
        }
        btn("Remove that protection") {
            dpm.setUserControlDisabledPackages(admin, emptyList()); log("removed user-control protection")
        }
        btn("Ask for battery exemption (system prompt)") {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        }
        btn("Try device-owner self-grant of battery exemption permission") {
            val ok = dpm.setPermissionGrantState(
                admin, packageName, Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
            log("self-grant battery perm call=$ok exempt=${powerManager().isIgnoringBatteryOptimizations(packageName)}")
        }
        btn("Auto time ON via DPM") { dpm.setAutoTimeEnabled(admin, true); log("auto time on") }
        btn("Auto time zone ON via DPM") { dpm.setAutoTimeZoneEnabled(admin, true); log("auto timezone on") }
        val minutes = input("Heartbeat minutes", Prefs.alarmMinutes(this).toString(), true)
        btn("START heartbeat alarm chain (overnight Doze test)") {
            Prefs.setAlarmMinutes(this, minutes.text.toString().toIntOrNull()?.coerceIn(1, 120) ?: 5)
            Prefs.setAlarmChain(this, true); Alarms.scheduleChain(this)
            log("heartbeat chain started every ${Prefs.alarmMinutes(this)} min")
        }
        btn("STOP heartbeat chain") {
            Prefs.setAlarmChain(this, false); Alarms.cancelChain(this); log("heartbeat chain stopped")
        }
        btn("Open exact-alarm permission screen") {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }

        title("Home app")
        btn("Pin the current Home app (persistent preferred activity)") { pinHome() }
        btn("Unpin") {
            Prefs.pinnedHome(this)?.let { dpm.clearPackagePersistentPreferredActivities(admin, it) }
            Prefs.setPinnedHome(this, null); log("home unpinned")
        }

        title("Camera / QR scan")
        btn("Try device-owner self-grant of CAMERA") {
            val ok = dpm.setPermissionGrantState(
                admin, packageName, Manifest.permission.CAMERA,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
            log("self-grant CAMERA call=$ok now=${granted(Manifest.permission.CAMERA)}")
        }
        btn("Scan a QR code (ZXing)") {
            if (!granted(Manifest.permission.CAMERA)) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
                log("camera permission requested; tap scan again after granting")
            } else {
                scan.launch(
                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setOrientationLocked(false).setBeepEnabled(false).setPrompt("Scan a QR code"),
                )
            }
        }

        title("Keystore")
        btn("ECDSA P-256 sign/verify + security level") { keystoreTest() }

        title("Network (be gentle: a few requests at most)")
        val url = input("URL (https)", "")
        val kb = input("Body size KB", "1", true)
        btn("POST JSON of that size") { post(url.text.toString(), kb.text.toString().toIntOrNull() ?: 1) }
        btn("GET") { get(url.text.toString()) }

        title("Companion nudge test")
        note(
            "Send from HA (Developer tools > Actions), notify.mobile_app_<phone>:\n" +
                "message: command_broadcast_intent\n" +
                "data:\n  intent_action: io.github.durkimat.phoneguard.lab.NUDGE\n" +
                "  intent_package_name: $packageName\n  intent_extras: \"a:1,b:hello\"\n" +
                "Then look for NUDGE in the log. Minimal flavour package is ...companion.android.minimal.",
        )

        title("Log")
        logView = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE }
        root.addView(logView)
        btn("Refresh log") { }
        btn("Copy log to clipboard") {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("lab log", LabLog.tail(this, 400)))
        }
        btn("Clear log") { LabLog.clear(this) }
        note("File: ${LabLog.file(this)}\nadb pull ${LabLog.file(this)}")
    }

    // ---- actions ---------------------------------------------------------------------------

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun powerManager() = getSystemService(PowerManager::class.java)

    private fun setRestriction(box: CheckBox, key: String) {
        val on = box.isChecked
        if (on && key == UserManager.DISALLOW_DEBUGGING_FEATURES) {
            box.isChecked = false
            AlertDialog.Builder(this)
                .setTitle("Cut adb?")
                .setMessage("This may disable USB debugging. It auto-reverts in 3 min and on reboot. Continue?")
                .setPositiveButton("Apply") { _, _ ->
                    act("apply debugging restriction") {
                        dpm.addUserRestriction(admin, key); Alarms.revertDebuggingIn(this, 3)
                        log("APPLIED $key (auto-revert in 3 min)")
                    }
                }
                .setNegativeButton("Cancel", null).show()
            return
        }
        if (on) dpm.addUserRestriction(admin, key) else dpm.clearUserRestriction(admin, key)
        log("${if (on) "APPLIED" else "CLEARED"} $key effective=${um().hasUserRestriction(key)}")
    }

    private fun um() = getSystemService(UserManager::class.java)

    private fun dumpRestrictions() {
        val ours = try {
            dpm.getUserRestrictions(admin).keySet().filter { dpm.getUserRestrictions(admin).getBoolean(it) }
        } catch (e: Exception) {
            listOf("(not owner: ${e.javaClass.simpleName})")
        }
        val all = um().userRestrictions.keySet().filter { um().userRestrictions.getBoolean(it) }
        log("restrictions ours=$ours")
        log("restrictions effective(all sources)=$all  <- differences = another admin/system app set them")
    }

    private fun loadApps() {
        val pm = packageManager
        val launcher = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        ).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        val hidden = Prefs.hidden(this).map { it to "$it (hidden by us)" }
        appList = (launcher + hidden).distinctBy { it.first }.sortedBy { it.second.lowercase() }
        appSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            appList.map { "${it.second}  [${it.first}]" },
        )
        log("loaded ${appList.size} launcher/hidden apps")
    }

    private fun selected(): String? = appList.getOrNull(appSpinner.selectedItemPosition)?.first

    private fun hideSelected(hide: Boolean) {
        val pkg = selected() ?: return log("no app selected")
        if (hide && Protected.isProtected(this, pkg)) return log("REFUSED: $pkg is protected")
        val t0 = SystemClock.elapsedRealtime()
        val ok = dpm.setApplicationHidden(admin, pkg, hide)
        log("setApplicationHidden($pkg,$hide) ok=$ok in ${SystemClock.elapsedRealtime() - t0} ms")
        if (ok) if (hide) Prefs.addHidden(this, pkg) else Prefs.removeHidden(this, pkg)
    }

    private fun suspendSelected(suspend: Boolean) {
        val pkg = selected() ?: return log("no app selected")
        if (suspend && Protected.isProtected(this, pkg)) return log("REFUSED: $pkg is protected")
        val failed = dpm.setPackagesSuspended(admin, arrayOf(pkg), suspend)
        log("setPackagesSuspended($pkg,$suspend) failed=${failed.toList()}")
    }

    private fun reportSelected() {
        val pkg = selected() ?: return log("no app selected")
        log(
            "state $pkg hidden=${dpm.isApplicationHidden(admin, pkg)} " +
                "suspended=${try { dpm.isPackageSuspended(admin, pkg) } catch (e: Exception) { e.javaClass.simpleName }}",
        )
    }

    private fun setGray(on: Boolean) {
        val a = Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer", 0)
        val b = Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer_enabled", if (on) 1 else 0)
        log("gray on=$on write=$a/$b")
    }

    private fun toggleGrayWatch() {
        if (grayObserver != null) {
            contentResolver.unregisterContentObserver(grayObserver!!)
            grayObserver = null; log("gray watcher stopped"); return
        }
        val key = "accessibility_display_daltonizer_enabled"
        val t0 = SystemClock.elapsedRealtime()
        grayObserver = object : ContentObserver(ui) {
            override fun onChange(selfChange: Boolean) {
                val v = Settings.Secure.getInt(contentResolver, key, 0)
                if (v == 0) {
                    try {
                        Settings.Secure.putInt(contentResolver, key, 1)
                        log("gray switched off -> RE-ASSERTED after ${(SystemClock.elapsedRealtime() - t0) / 1000}s")
                    } catch (e: Exception) {
                        log("re-assert failed: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
        contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false, grayObserver!!)
        log("gray watcher running: turn Colour correction off in Settings and watch the log")
    }

    private fun pinHome() {
        val home = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo ?: return log("no home found")
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(
            admin, filter, android.content.ComponentName(home.packageName, home.name),
        )
        Prefs.setPinnedHome(this, home.packageName)
        log("pinned home ${home.packageName}/${home.name}: now try to change the default Home app in Settings")
    }

    private fun keystoreTest() {
        val alias = "pg_lab_key"
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        kpg.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build(),
        )
        val kp = kpg.generateKeyPair()
        val msg = "phoneguard-lab".toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(kp.private); update(msg); sign()
        }
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(kp.public); update(msg); verify(sig)
        }
        val info = KeyFactory.getInstance(kp.private.algorithm, "AndroidKeyStore")
            .getKeySpec(kp.private, KeyInfo::class.java)
        val level = if (Build.VERSION.SDK_INT >= 31) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "STRONGBOX"
                else -> "other(${info.securityLevel})"
            }
        } else "n/a"
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        log("keystore ECDSA P-256 sign/verify=$ok sigLen=${sig.size} level=$level")
    }

    private fun post(url: String, kb: Int) {
        if (url.isBlank()) return log("enter a URL first")
        val body = "{\"t\":\"lab\",\"pad\":\"" + "x".repeat((kb.coerceIn(1, 512) * 1024) - 20) + "\"}"
        thread {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 15000
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("X-PG-Lab", "header-forward-test")
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                val resp = (if (code < 400) c.inputStream else c.errorStream)?.readBytes() ?: ByteArray(0)
                log("POST ${body.length}B -> $code in ${SystemClock.elapsedRealtime() - t0} ms, reply ${resp.size}B: ${String(resp).take(120)}")
            } catch (e: Exception) {
                log("POST failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun get(url: String) {
        if (url.isBlank()) return log("enter a URL first")
        thread {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 15000
                val code = c.responseCode
                val resp = (if (code < 400) c.inputStream else c.errorStream)?.readBytes() ?: ByteArray(0)
                log("GET -> $code in ${SystemClock.elapsedRealtime() - t0} ms, ${resp.size}B: ${String(resp).take(120)}")
            } catch (e: Exception) {
                log("GET failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // ---- status ----------------------------------------------------------------------------

    private fun refresh() {
        val usm = getSystemService(UsageStatsManager::class.java)
        val owner = Dpm.isOwner(this)
        val gs = { k: String -> Settings.Global.getString(contentResolver, k) }
        statusView.text = buildString {
            appendLine("API ${Build.VERSION.SDK_INT}  ${Build.MODEL}  ${Build.TYPE}")
            appendLine("deviceOwner=$owner adminActive=${dpm.isAdminActive(admin)}")
            appendLine("exactAlarm=${getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()}")
            appendLine("batteryExempt=${powerManager().isIgnoringBatteryOptimizations(packageName)} bucket=${usm.appStandbyBucket}")
            appendLine("WRITE_SECURE_SETTINGS=${granted(Manifest.permission.WRITE_SECURE_SETTINGS)} CAMERA=${granted(Manifest.permission.CAMERA)}")
            appendLine("autoTime=${gs(Settings.Global.AUTO_TIME)} autoTZ=${gs(Settings.Global.AUTO_TIME_ZONE)} adb=${gs(Settings.Global.ADB_ENABLED)}")
            appendLine("home=${Protected.currentHome(this@MainActivity)} pinned=${Prefs.pinnedHome(this@MainActivity)}")
            appendLine("gray=${Settings.Secure.getInt(contentResolver, "accessibility_display_daltonizer_enabled", 0)}")
            append("hiddenByUs=${Prefs.hidden(this@MainActivity)}")
        }
        restrictionBoxes.forEach { (box, key) -> box.isChecked = um().hasUserRestriction(key) }
        if (!::appSpinner.isInitialized || appList.isEmpty()) loadApps()
        refreshLog()
    }

    private fun refreshLog() {
        if (::logView.isInitialized) logView.text = LabLog.tail(this)
    }
}
