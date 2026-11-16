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

    /** The downloaded playlists (by playlist address): name, whether it was pinned before the download, and when. */
    fun offline(c: Context): Map<String, PlaylistOffline.Offline> = try {
        val j = JSONObject(prefs(c).getString("pl_offline", "{}") ?: "{}")
        j.keys().asSequence().associateWith { k -> j.getJSONObject(k).let { PlaylistOffline.Offline(it.optString("name"), it.optBoolean("pre"), it.optLong("at")) } }
    } catch (_: Exception) { emptyMap() }

    fun setOffline(c: Context, rec: Map<String, PlaylistOffline.Offline>) {
        val j = JSONObject()
        rec.forEach { (k, v) -> j.put(k, JSONObject().put("name", v.name).put("pre", v.prePinned).put("at", v.at)) }
        prefs(c).edit().putString("pl_offline", j.toString()).apply()
    }
}
