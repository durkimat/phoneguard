package io.github.durkimat.phoneguard.music

import android.content.Context
import org.json.JSONObject

/** What this phone remembers about playlists: the ones pinned and when each was last started (by playlist address). Kept on the phone only. */
object PlaylistMemory {
    private fun prefs(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("music", Context.MODE_PRIVATE)

    fun pins(c: Context): Set<String> = prefs(c).getStringSet("pl_pins", emptySet())?.toSet() ?: emptySet()
    fun setPins(c: Context, pins: Set<String>) { prefs(c).edit().putStringSet("pl_pins", pins.toSet()).apply() }

    fun played(c: Context): Map<String, Long> = try {
        val j = JSONObject(prefs(c).getString("pl_played", "{}") ?: "{}")
        j.keys().asSequence().associateWith { j.optLong(it) }
    } catch (_: Exception) { emptyMap() }

    fun markPlayed(c: Context, uri: String, now: Long = System.currentTimeMillis()) {
        if (!uri.startsWith("spotify:playlist:")) return
        val m = PlaylistOrder.prune(played(c) + (uri to now))
        prefs(c).edit().putString("pl_played", JSONObject(m as Map<*, *>).toString()).apply()
    }
}
