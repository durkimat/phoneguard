package io.github.durkimat.phoneguard.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import io.github.durkimat.phoneguard.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** One row in a Spotify list: a playlist, album, track or artist. */
data class SpItem(val uri: String, val label: String, val sub: String)
data class SpDevice(val id: String, val name: String, val type: String, val active: Boolean, val volume: Int)

/** What Spotify says is playing, on whichever device. */
data class SpPlayer(
    val deviceId: String, val deviceName: String, val deviceType: String, val volume: Int, val playing: Boolean,
    val title: String, val artist: String, val album: String, val positionMs: Long, val durationMs: Long, val at: Long,
    val shuffle: Boolean, val repeat: String,
    /** What it is playing from, as a Spotify address (a playlist, album or artist), or empty. */
    val context: String = "",
) {
    /** Where the track is now: the last report plus the time since, while it is playing. */
    fun position(now: Long): Long = if (playing && durationMs > 0) (positionMs + (now - at)).coerceIn(0, durationMs) else positionMs
}

/**
 * Spotify's Web API for this phone: sign-in once (PKCE, no secret on the phone), then the player state, the devices on the (shared)
 * account, playlists, search, and playing or moving playback. The Client ID is public by design; there is no client secret anywhere.
 */
object Spotify {
    /** From the config HA sends (see Config.spotifyClientId); the id used to sign in is remembered so refreshing the token always uses the same one. */
    fun configuredId(c: Context): String = io.github.durkimat.phoneguard.Store.config(c)?.spotifyClientId.orEmpty()
    private fun clientId(c: Context): String = sp(c).getString("client", null) ?: configuredId(c)
    fun configured(c: Context) = configuredId(c).isNotEmpty()
    const val REDIRECT = "io.github.durkimat.phoneguard://spotify-callback"
    private const val SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing playlist-read-private playlist-read-collaborative user-library-read"

    private fun sp(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("spotify", Context.MODE_PRIVATE)

    fun signedIn(c: Context): Boolean { adopt(c); return sp(c).getString("refresh", null) != null }

    /**
     * Sign-in done in HA: HA sends its refresh token in the settings. A new one is taken up once (the phone may then refresh
     * and rotate its own copy, so the one HA sent is remembered and not taken again); HA clearing it signs the phone out.
     */
    fun adopt(c: Context) {
        val want = io.github.durkimat.phoneguard.Store.config(c)?.spotifyRefresh.orEmpty()
        val p = sp(c)
        val had = p.getString("adopted", "").orEmpty()
        if (want.isNotEmpty() && want != had) {
            p.edit().putString("refresh", want).putString("client", configuredId(c)).putString("adopted", want).putLong("expires", 0).remove("access").apply()
            Log.log(c, "spotify: signed in from HA")
        } else if (want.isEmpty() && had.isNotEmpty()) {
            p.edit().clear().apply()
            Log.log(c, "spotify: signed out from HA")
        }
    }

    // ---- sign-in (authorisation code with PKCE)

    /** The page the person signs in on (in the phone's browser); Spotify sends them back to [REDIRECT]. */
    fun signInIntent(c: Context): Intent {
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        sp(c).edit().putString("verifier", verifier).putString("pending_client", configuredId(c)).apply()
        val url = "https://accounts.spotify.com/authorize?client_id=${configuredId(c)}&response_type=code&redirect_uri=${enc(REDIRECT)}" +
            "&code_challenge_method=S256&code_challenge=$challenge&scope=${enc(SCOPES)}"
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** The browser came back with a code: swap it for the tokens. Blocking; call off the main thread. */
    fun finishSignIn(c: Context, code: String): Boolean {
        val verifier = sp(c).getString("verifier", null) ?: return false
        val client = sp(c).getString("pending_client", null) ?: configuredId(c)
        val ok = token(c, "grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(REDIRECT)}&client_id=$client&code_verifier=${enc(verifier)}")
        if (ok) sp(c).edit().putString("client", client).remove("pending_client").apply()
        return ok
    }

    /** Forget this phone's tokens. The token HA sent stays remembered, so the same one is not taken up again. */
    fun signOut(c: Context) { val a = sp(c).getString("adopted", null); sp(c).edit().clear().apply(); if (a != null) sp(c).edit().putString("adopted", a).apply() }

    private fun token(c: Context, form: String): Boolean = try {
        val h = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        h.connectTimeout = 15_000; h.readTimeout = 20_000; h.requestMethod = "POST"; h.doOutput = true
        h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        h.outputStream.use { it.write(form.toByteArray()) }
        val code = h.responseCode
        val text = (if (code < 400) h.inputStream else h.errorStream)?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        if (code >= 400) {
            Log.log(c, "spotify token: HTTP $code ${text.take(80)}")
            if (code == 400 && text.contains("invalid_grant")) signOut(c)
            false
        } else {
            val j = JSONObject(text)
            val e = sp(c).edit().putString("access", j.getString("access_token")).putLong("expires", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000 - 60_000)
            if (j.has("refresh_token")) e.putString("refresh", j.getString("refresh_token"))
            e.apply(); true
        }
    } catch (e: Exception) { Log.log(c, "spotify token: ${e.javaClass.simpleName}"); false }

    private fun accessToken(c: Context): String? {
        adopt(c)
        val p = sp(c)
        val refresh = p.getString("refresh", null) ?: return null
        if (System.currentTimeMillis() >= p.getLong("expires", 0) && !token(c, "grant_type=refresh_token&refresh_token=${enc(refresh)}&client_id=${clientId(c)}")) return null
        return p.getString("access", null)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---- the Web API

    /** (HTTP status, body). Status 0 means no token or no network. */
    fun call(c: Context, method: String, path: String, body: String? = null): Pair<Int, String> {
        var retried = false
        while (true) {
            val t = accessToken(c) ?: return 0 to ""
            try {
                val h = URL("https://api.spotify.com/v1$path").openConnection() as HttpURLConnection
                h.connectTimeout = 15_000; h.readTimeout = 20_000; h.requestMethod = method
                h.setRequestProperty("Authorization", "Bearer $t")
                if (body != null) { h.doOutput = true; h.setRequestProperty("Content-Type", "application/json"); h.outputStream.use { it.write(body.toByteArray()) } }
                else if (method != "GET") { h.doOutput = true; h.setRequestProperty("Content-Length", "0"); h.outputStream.use { } }
                val code = h.responseCode
                val text = (if (code < 400) h.inputStream else h.errorStream)?.readBytes()?.toString(Charsets.UTF_8) ?: ""
                if (code == 401 && !retried) { retried = true; sp(c).edit().putLong("expires", 0).apply(); continue }
                if (code >= 400) Log.log(c, "spotify $method $path -> $code ${text.take(100)}")
                return code to text
            } catch (e: Exception) { Log.log(c, "spotify $path: ${e.javaClass.simpleName}"); return 0 to "" }
        }
    }

    fun player(c: Context): SpPlayer? {
        val (code, text) = call(c, "GET", "/me/player")
        if (code != 200 || text.isEmpty()) return null // 204: nothing is playing anywhere
        val j = JSONObject(text)
        val d = j.optJSONObject("device") ?: return null
        val item = j.optJSONObject("item")
        val artists = item?.optJSONArray("artists")
        return SpPlayer(
            d.optString("id"), d.optString("name"), d.optString("type"), d.optInt("volume_percent", 0), j.optBoolean("is_playing"),
            item?.optString("name").orEmpty(), (0 until (artists?.length() ?: 0)).joinToString(", ") { artists!!.getJSONObject(it).optString("name") },
            item?.optJSONObject("album")?.optString("name").orEmpty(), j.optLong("progress_ms"), item?.optLong("duration_ms") ?: 0L, System.currentTimeMillis(),
            j.optBoolean("shuffle_state"), j.optString("repeat_state", "off"), j.optJSONObject("context")?.optString("uri").orEmpty(),
        )
    }

    /** The name of a playlist, album or artist from its address ("" when unknown). */
    fun contextName(c: Context, uri: String): String {
        val parts = uri.split(":")
        if (parts.size != 3) return ""
        val path = when (parts[1]) { "playlist" -> "/playlists/${parts[2]}?fields=name"; "album" -> "/albums/${parts[2]}"; "artist" -> "/artists/${parts[2]}"; else -> return "" }
        val (code, text) = call(c, "GET", path)
        return if (code == 200) try { JSONObject(text).optString("name") } catch (_: Exception) { "" } else ""
    }

    fun devices(c: Context): List<SpDevice>? {
        val (code, text) = call(c, "GET", "/me/player/devices")
        if (code != 200) return null
        val a = JSONObject(text).optJSONArray("devices") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { SpDevice(it.optString("id"), it.optString("name"), it.optString("type"), it.optBoolean("is_active"), it.optInt("volume_percent", 0)) }
    }

    fun playlists(c: Context): List<SpItem>? {
        val (code, text) = call(c, "GET", "/me/playlists?limit=50")
        if (code != 200) return null
        val a = JSONObject(text).optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { SpItem(it.optString("uri"), it.optString("name"), (it.optJSONObject("tracks")?.optInt("total") ?: 0).let { n -> if (n > 0) "$n tracks" else "" }) }
    }

    fun search(c: Context, q: String): List<SpItem>? {
        val (code, text) = call(c, "GET", "/search?q=${enc(q)}&type=track,album,playlist&limit=8")
        if (code != 200) return null
        val j = JSONObject(text); val out = ArrayList<SpItem>()
        fun add(key: String, kind: String, sub: (JSONObject) -> String) {
            val a = j.optJSONObject(key)?.optJSONArray("items") ?: return
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { out.add(SpItem(it.optString("uri"), "$kind: " + it.optString("name"), sub(it))) }
        }
        val artists: (JSONObject) -> String = { o -> o.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") } }.orEmpty() }
        add("tracks", "Song", artists); add("albums", "Album", artists); add("playlists", "Playlist") { o -> o.optJSONObject("owner")?.optString("display_name").orEmpty() }
        return out
    }

    /** Play something (a playlist or album link as a context, a track link as itself) on a device. */
    fun play(c: Context, uri: String, deviceId: String?): Int {
        val body = if (uri.startsWith("spotify:track:")) JSONObject().put("uris", JSONArray().put(uri)) else JSONObject().put("context_uri", uri)
        return call(c, "PUT", "/me/player/play" + (if (deviceId != null) "?device_id=$deviceId" else ""), body.toString()).first
    }

    fun transfer(c: Context, deviceId: String, play: Boolean = true): Int =
        call(c, "PUT", "/me/player", JSONObject().put("device_ids", JSONArray().put(deviceId)).put("play", play).toString()).first

    fun pause(c: Context) = call(c, "PUT", "/me/player/pause").first
    fun resume(c: Context) = call(c, "PUT", "/me/player/play").first
    fun next(c: Context) = call(c, "POST", "/me/player/next").first
    fun previous(c: Context) = call(c, "POST", "/me/player/previous").first
    fun volume(c: Context, pct: Int) = call(c, "PUT", "/me/player/volume?volume_percent=${pct.coerceIn(0, 100)}").first
    /** state is "off", "context" (the playlist or album) or "track". */
    fun repeat(c: Context, state: String) = call(c, "PUT", "/me/player/repeat?state=$state").first
    fun shuffle(c: Context, on: Boolean) = call(c, "PUT", "/me/player/shuffle?state=$on").first
}
