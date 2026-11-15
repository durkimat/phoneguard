package io.github.durkimat.phoneguard

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Which kind of phone this is (docs/plan-two-editions.md, decided 2026-11-15): FIRST_BOOT until the person chooses on the first-run screen, then
 * OPEN (nothing locked, no companion, everything set on the phone) or FAMILY (device owner, enforced from a parent's companion).
 * The choice is made once. FAMILY to OPEN is the parent's Release; OPEN to FAMILY is a guided factory reset, after which the phone is FIRST_BOOT again.
 * A phone that already was a device owner before this existed (an upgraded Family phone) counts as FAMILY.
 */
enum class EditionState { FIRST_BOOT, OPEN, FAMILY }

object Edition {
    private fun sp(c: Context) = c.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("pg-edition", Context.MODE_PRIVATE)

    fun get(c: Context): EditionState {
        sp(c).getString("edition", null)?.let { s -> try { return EditionState.valueOf(s) } catch (_: Exception) { } }
        return if (Dpm.isOwner(c)) EditionState.FAMILY.also { set(c, it) } else EditionState.FIRST_BOOT
    }

    /** Remembers the edition and mirrors it into Settings.Global so the first-boot service (which runs as root) knows when to stop waiting. */
    fun set(c: Context, e: EditionState) {
        sp(c).edit().putString("edition", e.name).apply()
        try { if (e != EditionState.FIRST_BOOT) Settings.Global.putString(c.contentResolver, "pg_edition", e.name.lowercase()) } catch (ex: Exception) { Log.log(c, "edition mirror: ${ex.javaClass.simpleName}") }
        Log.log(c, "edition: $e")
    }

    // ---- the options of an Open phone (kept on the phone; a Family phone takes its configuration from the parent) ----
    @Volatile private var openCache: Pair<String, io.github.durkimat.phoneguard.core.Config>? = null

    fun openOptions(c: Context): Map<String, String> = try {
        val j = org.json.JSONObject(sp(c).getString("open.opts", "{}") ?: "{}")
        j.keys().asSequence().associateWith { j.optString(it) }
    } catch (_: Exception) { emptyMap() }

    fun setOpenOption(c: Context, key: String, value: String) {
        val j = org.json.JSONObject(openOptions(c) as Map<*, *>); j.put(key, value)
        sp(c).edit().putString("open.opts", j.toString()).apply()
        openCache = null
    }

    /** What Store.config returns for a phone that is not a Family phone. */
    fun openConfig(c: Context): io.github.durkimat.phoneguard.core.Config {
        val t = sp(c).getString("open.opts", "{}") ?: "{}"
        openCache?.let { if (it.first == t) return it.second }
        return io.github.durkimat.phoneguard.core.OpenConfig.build(openOptions(c)).also { openCache = t to it }
    }

    /** Family enforcement only runs on a FAMILY phone that really is the device owner. */
    fun enforcing(c: Context): Boolean = get(c) == EditionState.FAMILY && Dpm.isOwner(c)

    /** The person chose "Open phone" on the first-run screen. Returns false when the choice is no longer open. */
    fun chooseOpen(c: Context): Boolean {
        if (get(c) != EditionState.FIRST_BOOT) return false
        set(c, EditionState.OPEN)
        Controller.evaluateAsync(c, "edition open")
        return true
    }

    /**
     * Family enrolment succeeded (a pairing was confirmed): ask the first-boot service to make us device owner and wait for it. Blocking (up to
     * about 90 s), so call it off the main thread. Returns "" when this is now a Family phone, otherwise a short reason.
     */
    fun becomeFamily(c: Context): String {
        val app = c.applicationContext
        if (get(app) == EditionState.OPEN) return "This is an Open phone. To make it a Family phone, erase it first."
        if (!Dpm.isOwner(app)) {
            try {
                Settings.Global.putString(app.contentResolver, "pg_owner_req", "1")
            } catch (e: Exception) { return "Cannot ask for owner rights: ${e.javaClass.simpleName}" }
            Log.log(app, "edition: asked for device owner")
            var waited = 0
            while (waited < 90_000 && !Dpm.isOwner(app)) {
                if (Settings.Global.getString(app.contentResolver, "pg_owner_req") == "-1") return "Android refused to make this a Family phone (the phone has already been set up with an account or extra users)"
                Thread.sleep(1_000); waited += 1_000
            }
            if (!Dpm.isOwner(app)) return "Timed out waiting for owner rights"
        }
        set(app, EditionState.FAMILY)
        Controller.evaluate(app, "edition family")
        return ""
    }

    /** The parent released the phone: it carries on as an Open phone. */
    fun afterRelease(c: Context) { set(c, EditionState.OPEN) }

    /** The guided factory reset behind "Open to Family" (and the way back to the first-run screen). The phone restarts erased. */
    fun eraseAndRestart(c: Context) {
        Log.log(c, "edition: factory reset requested")
        c.sendBroadcast(Intent("android.intent.action.FACTORY_RESET").setPackage("android").addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra("android.intent.extra.REASON", "Phone Guard: change phone type"))
    }
}
