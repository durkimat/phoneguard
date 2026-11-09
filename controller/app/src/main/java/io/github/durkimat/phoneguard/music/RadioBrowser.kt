package io.github.durkimat.phoneguard.music

import android.content.Context
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.core.RadioStation
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A station found by browsing or searching. */
data class RadioItem(val name: String, val sub: String, val url: String)

/**
 * Looks stations up in the community directory at radio-browser.info (free, no key; it asks clients to say who they are). Only https
 * streams are offered, because Android blocks plain http. Always called off the main thread; a null answer means it could not be reached.
 */
object RadioBrowser {
    private const val AGENT = "PhoneGuard/0.1 (family phone; github.com/durkimat/phoneguard)"
    @Volatile private var host: String? = null

    val genres = listOf("pop", "rock", "dance", "classical", "jazz", "country", "hip hop", "70s", "80s", "90s", "chillout", "news", "talk", "sport", "kids", "christmas")
    val countries = listOf("United Kingdom" to "GB", "Ireland" to "IE", "United States" to "US", "Australia" to "AU", "Canada" to "CA", "France" to "FR", "Germany" to "DE", "Spain" to "ES", "Italy" to "IT", "Netherlands" to "NL")

    fun top(country: String): List<RadioItem>? = query("countrycode=${enc(country)}")
    fun byTag(tag: String): List<RadioItem>? = query("tag=${enc(tag)}&tagExact=true")
    fun byCountry(code: String): List<RadioItem>? = query("countrycode=${enc(code)}")
    fun search(name: String): List<RadioItem>? = query("name=${enc(name)}")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun query(filter: String): List<RadioItem>? = try {
        val text = get("/json/stations/search?$filter&order=clickcount&reverse=true&limit=60&hidebroken=true&is_https=true")
        val a = JSONArray(text)
        val seen = HashSet<String>()
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val url = o.optString("url_resolved").ifEmpty { o.optString("url") }
            val name = o.optString("name").trim()
            if (name.isEmpty() || !url.startsWith("https://") || !seen.add(name.lowercase())) null
            else RadioItem(name, listOf(o.optString("codec").takeIf { it.isNotEmpty() && it != "UNKNOWN" }, o.optInt("bitrate").takeIf { it > 0 }?.let { "${it}k" }, o.optString("countrycode").takeIf { it.isNotEmpty() }).filterNotNull().joinToString(" "), url)
        }.take(40)
    } catch (e: Exception) { null }

    private fun get(path: String): String {
        var last: Exception? = null
        for (attempt in 0 until 2) {
            val h = host ?: pickHost().also { host = it }
            try {
                val c = URL("https://$h$path").openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 8000; c.setRequestProperty("User-Agent", AGENT)
                if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                return c.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) { last = e; host = null }
        }
        throw last ?: java.io.IOException("no answer")
    }

    /** The directory runs several mirrors; ask for the list and take one at random, else a known one. */
    private fun pickHost(): String = try {
        val c = URL("https://all.api.radio-browser.info/json/servers").openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000; c.setRequestProperty("User-Agent", AGENT)
        val a = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        (0 until a.length()).map { a.getJSONObject(it).getString("name") }.distinct().random()
    } catch (e: Exception) { "de1.api.radio-browser.info" }
}

/** The child's own stations, kept on the phone: added from a result list or Now playing, shown after the parent's list when the mode allows any station. */
object RadioFavs {
    private fun prefs(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("radio", Context.MODE_PRIVATE)

    fun all(c: Context): List<RadioStation> = try {
        val a = JSONArray(prefs(c).getString("favs", "[]"))
        (0 until a.length()).map { RadioStation(a.getJSONObject(it).getString("name"), a.getJSONObject(it).getString("url")) }
    } catch (e: Exception) { Log.log(c, "radio favourites: ${e.javaClass.simpleName}"); emptyList() }

    fun has(c: Context, url: String) = all(c).any { it.url == url }

    /** Adds the station, or removes it if it was there. Returns true when it was added. */
    fun toggle(c: Context, name: String, url: String): Boolean {
        val cur = all(c)
        val added = cur.none { it.url == url }
        val next = if (added) cur + RadioStation(name.take(40), url) else cur.filter { it.url != url }
        prefs(c).edit().putString("favs", JSONArray(next.map { JSONObject().put("name", it.name).put("url", it.url) }).toString()).apply()
        return added
    }
}
