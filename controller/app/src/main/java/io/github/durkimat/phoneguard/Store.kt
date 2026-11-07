package io.github.durkimat.phoneguard

import android.content.Context
import io.github.durkimat.phoneguard.core.Boost
import io.github.durkimat.phoneguard.core.Config
import org.json.JSONObject

/** Everything we persist: the pushed config, the boost, and what we changed on the phone. */
object Store {
    /**
     * Device-protected storage, so we keep working after a reboot before the PIN is entered
     * (credential-encrypted storage is locked until first unlock).
     */
    private fun sp(c: Context): android.content.SharedPreferences {
        val dp = c.applicationContext.createDeviceProtectedStorageContext()
        if (!migrated) { dp.moveSharedPreferencesFrom(c.applicationContext, "pg"); migrated = true }
        return dp.getSharedPreferences("pg", Context.MODE_PRIVATE)
    }

    @Volatile private var migrated = false

    @Volatile private var cached: Pair<String, Config>? = null

    fun configText(c: Context): String? = sp(c).getString("config", null)

    fun setConfig(c: Context, text: String) {
        Config.parse(text) // reject bad JSON before saving, so a bad push never replaces a good config
        sp(c).edit().putString("config", text).apply()
        cached = null
    }

    fun clearConfig(c: Context) { sp(c).edit().remove("config").apply(); cached = null }

    fun config(c: Context): Config? {
        val t = configText(c) ?: return null
        cached?.let { if (it.first == t) return it.second }
        return try {
            Config.parse(t).also { cached = t to it }
        } catch (e: Exception) {
            Log.log(c, "config unreadable (${e.javaClass.simpleName}), using Emergency mode")
            null
        }
    }

    fun boost(c: Context): Boost? {
        val t = sp(c).getString("boost", null) ?: return null
        return try { JSONObject(t).let { Boost(it.getString("mode"), it.getLong("until")) } } catch (_: Exception) { null }
    }

    fun setBoost(c: Context, b: Boost?) {
        val e = sp(c).edit()
        if (b == null) e.remove("boost") else e.putString("boost", JSONObject().put("mode", b.mode).put("until", b.until).toString())
        e.apply()
    }

    /** Packages we hid. We only ever unhide these, so we never undo another app's hiding. */
    fun hidden(c: Context): Set<String> = sp(c).getStringSet("hidden", emptySet()) ?: emptySet()
    fun setHidden(c: Context, s: Set<String>) { sp(c).edit().putStringSet("hidden", s.toSet()).apply() }

    /** Restriction keys we set. Only these are ever cleared. */
    fun restrictions(c: Context): Set<String> = sp(c).getStringSet("restrictions", emptySet()) ?: emptySet()
    fun setRestrictions(c: Context, s: Set<String>) { sp(c).edit().putStringSet("restrictions", s.toSet()).apply() }

    fun lastWarned(c: Context) = sp(c).getLong("lastWarned", 0L)
    fun setLastWarned(c: Context, t: Long) { sp(c).edit().putLong("lastWarned", t).apply() }

    fun wantGray(c: Context) = sp(c).getBoolean("wantGray", false)
    fun setWantGray(c: Context, v: Boolean) { sp(c).edit().putBoolean("wantGray", v).apply() }

    fun menuKeySaved(c: Context): String? = sp(c).getString("menuKeySaved", null)
    fun setMenuKeySaved(c: Context, t: String?) { val e = sp(c).edit(); if (t == null) e.remove("menuKeySaved") else e.putString("menuKeySaved", t); e.apply() }

    fun pregranted(c: Context): Set<String> = sp(c).getStringSet("pregranted", emptySet()) ?: emptySet()
    fun addPregranted(c: Context, k: String) { sp(c).edit().putStringSet("pregranted", pregranted(c) + k).apply() }

    fun linkText(c: Context): String? = sp(c).getString("link", null)
    fun setLinkText(c: Context, t: String?) { val e = sp(c).edit(); if (t == null) e.remove("link") else e.putString("link", t); e.apply() }

    fun summary(c: Context, s: String) { sp(c).edit().putString("summary", s).apply() }
    fun summary(c: Context): String = sp(c).getString("summary", "not evaluated yet") ?: ""
}

/** Wall clock, with an offset that exists only in debug builds so the rig can jump through the timetable. */
object Clock {
    @Volatile var offset = 0L
    fun now() = System.currentTimeMillis() + offset
    /** Real milliseconds until `target` on this clock. */
    fun untilReal(target: Long) = (target - now()).coerceAtLeast(1_000L)
}
