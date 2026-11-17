package io.github.durkimat.phoneguard.where

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.durkimat.phoneguard.companion.WorkerLink
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * The map pictures under the route, fetched through the Worker (`GET /api/geo/tile/z/x/y`, signed) one at a time (the signature counter must rise in order),
 * turned into four LCD tones ([Tone]) and kept in memory only while the map is open ([release] empties it). A tile that cannot be had simply leaves the
 * plain background; [problem] says why the last one failed (429 used up for today, 403 off in this mode, 503 or -1 not reachable).
 */
object TileMap {
    private const val KEEP = 40            // tiles held at once
    private const val SESSION_MAX = 120    // tiles asked for in one visit to the map, so a wandering dot cannot run the day's allowance down
    private val cache = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>?) = size > KEEP }
    private val pending = HashSet<String>()
    private val worker = Executors.newSingleThreadExecutor()
    private var asked = 0
    private var retryAt = 0L
    @Volatile var problem = 0
        private set

    @Synchronized fun get(key: String): Bitmap? = cache[key]
    @Synchronized fun have(): Boolean = cache.isNotEmpty()

    /** Asks for whichever of [tiles] are not here yet; [onArrive] runs (on a worker thread) after each one lands. */
    @Synchronized fun want(c: Context, tiles: List<Tile>, onArrive: () -> Unit) {
        if (problem == 403 || problem == 429) return
        if (problem != 0 && System.currentTimeMillis() < retryAt) return
        val app = c.applicationContext
        for (t in tiles) {
            if (cache.containsKey(t.key) || !pending.add(t.key)) continue
            if (asked >= SESSION_MAX) { pending.remove(t.key); continue }
            asked++
            worker.execute { try { fetch(app, t); onArrive() } finally { synchronized(this) { pending.remove(t.key) } } }
        }
    }

    private fun fetch(c: Context, t: Tile) {
        if (problem == 403 || problem == 429) return
        var r = WorkerLink.tile(c, t.z, t.x, t.y)
        if (r != null && r.first == 409) r = WorkerLink.tile(c, t.z, t.x, t.y) // the counter was behind: it has skipped forward, ask once more
        val code = r?.first ?: -1
        if (code == 200) {
            val bmp = decode(r!!.second)
            synchronized(this) { if (bmp != null) { cache[t.key] = bmp; problem = 0 } }
        } else synchronized(this) { problem = code; retryAt = System.currentTimeMillis() + 30_000L }
    }

    private fun decode(png: ByteArray): Bitmap? {
        return try {
        val src = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
        val px = IntArray(src.width * src.height); src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ALPHA_8)
        out.copyPixelsFromBuffer(ByteBuffer.wrap(Tone.ink(px)))
        src.recycle(); out
        } catch (_: Exception) { null }
    }

    /** A new visit to the map: forget old failures and count again. */
    @Synchronized fun begin() { problem = 0; asked = 0; retryAt = 0 }

    /** The map was left: free the pictures. */
    @Synchronized fun release() { cache.clear(); problem = 0; asked = 0 }
}
