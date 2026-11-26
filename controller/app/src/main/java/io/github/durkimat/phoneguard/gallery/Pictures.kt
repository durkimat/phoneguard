package io.github.durkimat.phoneguard.gallery

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
import io.github.durkimat.media.Media
import io.github.durkimat.phoneguard.camera.Dither
import io.github.durkimat.phoneguard.camera.Effect
import io.github.durkimat.phoneguard.camera.Effects
import java.util.Collections
import java.util.concurrent.Executors

/** Loading pictures and turning them into the LCD look, for the gallery, a message's page and its full view. */
object Pictures {
    /**
     * A picture no more than about [maxPx] on its long side. [source] is a media-index address (the gallery) or a file path (a
     * message's media); a [video] file gives one of its frames. Null when it cannot be read.
     */
    fun load(c: Context, source: String, video: Boolean, maxPx: Int): Bitmap? = try {
        if (!source.startsWith("/")) c.contentResolver.loadThumbnail(Uri.parse(source), Size(maxPx, maxPx), null)
        else if (video) Media.videoFrame(source) else Media.bitmap(source, maxPx)
    } catch (_: Exception) { null }

    /** [b] as [cols] by [rows] cells in two theme colours (the camera's Tones look); [square] crops the middle square first. */
    fun dithered(b: Bitmap, cols: Int, rows: Int, square: Boolean, lcd: Int, ink: Int): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val s = if (square) minOf(w, h) else 0
        val sw = if (square) s else w; val sh = if (square) s else h
        val ox = if (square) (w - s) / 2 else 0; val oy = if (square) (h - s) / 2 else 0
        val cells = Dither.sample(sw, sh, 0, false, cols, rows) { x, y -> luma(px[(y + oy) * w + x + ox]) }
        return Bitmap.createBitmap(Effects.render(Effect.TONES, cells, cols, rows, lcd, ink, fine = false), cols, rows, Bitmap.Config.ARGB_8888)
    }

    /** Brightness of one pixel; what is see-through counts as light, so a sticker sits on the screen and not on a black square. */
    fun luma(argb: Int): Int {
        val a = argb ushr 24
        val v = (((argb shr 16) and 255) * 299 + ((argb shr 8) and 255) * 587 + (argb and 255) * 114) / 1000
        return (v * a + 255 * (255 - a)) / 255
    }

    /** [b] scaled to fit [cols] by [rows] cells, keeping its shape, then dithered. */
    fun ditheredToFit(b: Bitmap, cols: Int, rows: Int, lcd: Int, ink: Int): Bitmap {
        val k = minOf(cols.toFloat() / b.width, rows.toFloat() / b.height)
        return dithered(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), false, lcd, ink)
    }
}

/** The dithered pictures shown on message pages: the skin reads what is ready, the activity asks for what is not. */
object ChatPictures {
    /** The picture's box on a message page, in 3-pixel cells (448 by 286 design pixels). */
    private const val COLS = 148
    private const val ROWS = 94
    private val cache = LruCache<String, Bitmap>(12)
    private val pending: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val loader = Executors.newSingleThreadExecutor()
    private fun key(path: String, lcd: Int, ink: Int) = "$path|$lcd|$ink"

    fun peek(path: String, lcd: Int, ink: Int): Bitmap? = cache.get(key(path, lcd, ink))

    /** Loads the picture if it is not ready or on the way, then calls [done] (on the loader thread). */
    fun ensure(c: Context, path: String, video: Boolean, lcd: Int, ink: Int, done: () -> Unit) {
        val k = key(path, lcd, ink)
        if (cache.get(k) != null || !pending.add(k)) return
        val app = c.applicationContext
        loader.execute {
            try {
                val b = Pictures.load(app, path, video, 640)
                if (b != null) { cache.put(k, Pictures.ditheredToFit(b, COLS, ROWS, lcd, ink)); done() }
            } catch (_: Exception) { } finally { pending.remove(k) }
        }
    }

    /** Forgets everything (a message was deleted, or WhatsApp was unlinked). */
    fun clear() = cache.evictAll()
}
