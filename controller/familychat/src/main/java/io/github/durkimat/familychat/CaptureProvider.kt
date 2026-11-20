package io.github.durkimat.familychat

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Gives the system camera one private file to write a photo or video into (the camera app needs a content address, and this app needs no
 * camera permission that way). It serves only files in the app's capture folder, and only to an app that was handed the address.
 */
class CaptureProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.lastPathSegment ?: throw SecurityException("no file")
        if (name.contains('/') || name.contains("..")) throw SecurityException("bad name")
        val f = File(File(context!!.cacheDir, "capture").apply { mkdirs() }, name)
        val m = ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
        return ParcelFileDescriptor.open(f, if (mode.contains("w")) m else ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    companion object {
        const val AUTHORITY = "io.github.durkimat.familychat.capture"
        fun uriFor(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")
        fun file(c: android.content.Context, name: String) = File(File(c.cacheDir, "capture").apply { mkdirs() }, name)
    }
}
