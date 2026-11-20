package io.github.durkimat.familychat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/** Pictures, stickers, GIFs and videos on this phone. Everything lives in the app's private folders, never the gallery. */
object Media {
    private const val CACHE_LIMIT = 200L * 1024 * 1024

    /** A picture file as a bitmap no larger than about [maxPx] on its long side. */
    fun bitmap(path: String, maxPx: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxPx) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (_: Throwable) { null }

    /** A sticker or picture as a drawable; an animated WebP or GIF animates (the caller starts it). */
    fun drawable(path: String): Drawable? = try {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(path)))
    } catch (_: Throwable) { null }

    fun start(d: Drawable?) { (d as? AnimatedImageDrawable)?.let { it.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; it.start() } }

    /** A frame of a video file (the thumbnail of a downloaded video), or null. */
    fun videoFrame(path: String): Bitmap? = try {
        MediaMetadataRetriever().use { it.setDataSource(path); it.getFrameAtTime(500_000) }
    } catch (_: Throwable) { null }

    fun videoSeconds(c: Context, uri: Uri): Int = try {
        MediaMetadataRetriever().use { it.setDataSource(c, uri); ((it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt() }
    } catch (_: Throwable) { 0 }

    /** True on Wi-Fi (or any network that is not metered). */
    fun unmetered(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun dir(c: Context, name: String): File = File(c.filesDir, name).apply { mkdirs() }

    /** A picture from [uri] made safe to send: turned upright, scaled to a long edge of 1600, saved as a new JPEG (which has no EXIF, so no place or camera details). Returns file, width, height. */
    fun preparePhoto(c: Context, uri: Uri, out: File): Triple<File, Int, Int>? = try {
        val rot = c.contentResolver.openInputStream(uri)!!.use { s ->
            when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
            }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= Rich.PHOTO_LONG_EDGE) sample *= 2
        var bmp = c.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
        if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
        val (w, h) = Rich.fit(bmp.width, bmp.height)
        if (w != bmp.width) bmp = Bitmap.createScaledBitmap(bmp, w, h, true)
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        Triple(out, bmp.width, bmp.height)
    } catch (_: Throwable) { null }

    /** Removes media files no message points at any more, and the oldest ones beyond the size limit (their messages show a plain marker or can be fetched again). */
    fun cleanup(c: Context) {
        try {
            val dir = File(Fc.store().let { c.filesDir }, "wa/media")
            if (!dir.isDirectory) return
            val used = Fc.store().mediaPathsInUse()
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            for (f in files) if (f.absolutePath !in used && !f.name.startsWith("own-")) f.delete()
            var total = dir.listFiles()?.sumOf { it.length() } ?: 0L
            for (f in (dir.listFiles() ?: emptyArray()).sortedBy { it.lastModified() }) { if (total <= CACHE_LIMIT) break; total -= f.length(); f.delete() }
        } catch (_: Throwable) { }
    }
}
