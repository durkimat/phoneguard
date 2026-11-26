package io.github.durkimat.phoneguard.gallery

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns as F
import io.github.durkimat.phoneguard.Log

/** Reads the phone's pictures and videos from Android's media index (no files are opened here). */
object GalleryData {
    /** A safety stop on the rows read; [GalleryModel] then keeps the newest by [time]. */
    private const val READ_LIMIT = 20000

    /** When a picture was taken, in epoch milliseconds: the date taken if the file records one, else when it arrived on the phone. */
    fun time(takenMs: Long, addedSeconds: Long): Long = if (takenMs > 0) takenMs else addedSeconds * 1000

    fun load(c: Context): List<GItem> {
        val list = ArrayList<GItem>()
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val cols = arrayOf(F._ID, F.MEDIA_TYPE, F.DISPLAY_NAME, F.DATE_ADDED, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.DATE_TAKEN)
            val sel = "${F.MEDIA_TYPE} IN (${F.MEDIA_TYPE_IMAGE}, ${F.MEDIA_TYPE_VIDEO})"
            c.contentResolver.query(uri, cols, sel, null, "${F.DATE_ADDED} DESC")?.use { cur ->
                while (cur.moveToNext() && list.size < READ_LIMIT) {
                    val video = cur.getInt(1) == F.MEDIA_TYPE_VIDEO
                    list.add(GItem(ContentUris.withAppendedId(uri, cur.getLong(0)).toString(), video, cur.getString(2) ?: "", time(cur.getLong(6), cur.getLong(3)), cur.getString(4) ?: "", (cur.getLong(5) / 1000).toInt()))
                }
            }
        } catch (e: Exception) { Log.log(c, "gallery: ${e.javaClass.simpleName}") }
        return list
    }

    /** "0:24" for a video's length. */
    fun clock(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
}
