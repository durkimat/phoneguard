package io.github.durkimat.phoneguard.where

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The last ten places the child went to (name, area, position), newest first, kept on the phone only. */
object RecentPlaces {
    const val MAX = 10

    /** [p] first, any earlier copy of the same place removed (same name and area, or the same spot to about 10 m), the list cut to [MAX]. */
    fun add(list: List<Place>, p: Place): List<Place> {
        val rest = list.filterNot { same(it, p) }
        return (listOf(Place(p.name, p.kind, p.area, p.lat, p.lon, null)) + rest).take(MAX)
    }

    private fun same(a: Place, b: Place) = (a.name.equals(b.name, true) && a.area.equals(b.area, true)) || (Math.abs(a.lat - b.lat) < 1e-4 && Math.abs(a.lon - b.lon) < 1e-4)

    fun encode(list: List<Place>): String = JSONArray().also { a -> list.forEach { a.put(JSONObject().put("name", it.name).put("kind", it.kind).put("area", it.area).put("lat", it.lat).put("lon", it.lon)) } }.toString()

    fun decode(text: String?): List<Place> = try {
        val a = JSONArray(text ?: "[]")
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> if (o.has("lat") && o.has("lon") && o.optString("name").isNotBlank()) Place(o.optString("name"), o.optString("kind"), o.optString("area"), o.getDouble("lat"), o.getDouble("lon"), null) else null } }.take(MAX)
    } catch (_: Exception) { emptyList() }

    private fun sp(c: Context) = c.getSharedPreferences("where", Context.MODE_PRIVATE)
    fun load(c: Context): List<Place> = decode(sp(c).getString("recent", null))
    fun remember(c: Context, p: Place) { sp(c).edit().putString("recent", encode(add(load(c), p))).apply() }
}
