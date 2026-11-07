package io.github.durkimat.phoneguard

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import io.github.durkimat.phoneguard.core.Config

/**
 * Apps that must never be hidden: hiding a service package can break the phone, and the child must
 * always be able to call, text and reach Settings. Layers (spec 4e): dynamic, hard, default-editable,
 * then parent additions. Only apps with a launcher icon are ever hidden at all.
 */
object Protected {
    private val hard = setOf(
        "com.android.settings", "com.android.dialer", "com.android.messaging",
        "com.android.contacts", "com.android.documentsui", "com.google.android.gms",
        "com.google.android.gsf", "com.android.vending", "io.github.sspanak.tt9",
        "com.android.inputmethod.latin", "com.android.launcher3", "com.android.systemui",
        "com.android.phone", "com.android.server.telecom", "com.android.providers.telephony",
        "com.android.webview", "com.android.packageinstaller", "com.android.permissioncontroller",
        "eu.dumbdroid.deviceowner", "eu.dumbdroid.updater", "eu.dumbdroid.settingspanel",
        "com.android.emergency", "com.android.cellbroadcastreceiver",
        "com.android.cellbroadcastreceiver.module", "me.phh.treble.app",
        "io.homeassistant.companion.android", "io.homeassistant.companion.android.minimal",
    )

    /** Protected by default, but a parent may choose to manage them (listed in `unprotect`). */
    private val editable = setOf("com.android.deskclock")

    fun dynamic(c: Context): Set<String> {
        val out = mutableSetOf(c.packageName)
        c.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName?.let(out::add)
        Settings.Secure.getString(c.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')?.let(out::add)
        try { c.getSystemService(TelecomManager::class.java).defaultDialerPackage?.let(out::add) } catch (_: Exception) { }
        try { Telephony.Sms.getDefaultSmsPackage(c)?.let(out::add) } catch (_: Exception) { }
        try { android.webkit.WebView.getCurrentWebViewPackage()?.packageName?.let(out::add) } catch (_: Exception) { }
        return out
    }

    fun all(c: Context, cfg: Config?): Set<String> =
        dynamic(c) + hard + (editable - (cfg?.unprotect ?: emptySet())) + (cfg?.protectExtra ?: emptySet())
}
