package io.github.durkimat.phoneguard.camera

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore

/**
 * Makes the extra copy of a photo for a shooting effect, from the saved colour JPEG, at a much finer grain than the viewfinder:
 * the dots effect uses cells of two pixels on a picture of up to 1440 on the long side. Saved as a PNG beside the original; the
 * original is never touched.
 */
object EffectPhoto {
    private const val LONG_SIDE = 1440

    /** The colours the effect paints with, as (screen colour, ink colour). Dots and tones follow the launcher theme. */
    fun palette(e: Effect, lcd: Int, ink: Int): Pair<Int, Int> = when (e) {
        Effect.MONO -> 0xFFFFFFFF.toInt() to 0xFF000000.toInt()
        Effect.WARM -> 0xFFF6E7C8.toInt() to 0xFF2B1A0A.toInt()
        else -> lcd to ink
    }

    /** Turn decoded pixels into the effect picture. [cell] pixels per dot (1 for smooth effects). */
    fun render(px: IntArray, w: Int, h: Int, e: Effect, lcd: Int, ink: Int): Bitmap {
        val cell = if (e == Effect.DOTS) 2 else 1
        val cols = (w / cell).coerceAtLeast(1); val rows = (h / cell).coerceAtLeast(1)
        val cells = Dither.sample(w, h, 0, false, cols, rows) { x, y -> val c = px[y * w + x]; (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000 }
        val (bright, dark) = palette(e, lcd, ink)
        val out = if (e == Effect.DOTS) {
            val inkCells = Dither.twoTone(cells, cols, true)
            IntArray(inkCells.size) { if (inkCells[it]) dark or (0xFF shl 24) else bright or (0xFF shl 24) }
        } else Dither.ramp(cells, bright, dark)
        val small = Bitmap.createBitmap(out, cols, rows, Bitmap.Config.ARGB_8888)
        return if (cell == 1) small else Bitmap.createScaledBitmap(small, cols * cell, rows * cell, false).also { small.recycle() }
    }

    /** Decode the saved photo, apply the effect and store it next to the original. Returns the new item's uri, or null. */
    fun make(cr: ContentResolver, source: Uri, name: String, e: Effect, lcd: Int, ink: Int): Uri? {
        if (e == Effect.NONE) return null
        val src = ImageDecoder.createSource(cr, source)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val big = maxOf(info.size.width, info.size.height)
            if (big > LONG_SIDE) { val k = LONG_SIDE.toFloat() / big; decoder.setTargetSize((info.size.width * k).toInt(), (info.size.height * k).toInt()) }
        }
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        bmp.recycle()
        val result = render(px, w, h, e, lcd, ink)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${name}_${e.extra}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        cr.openOutputStream(uri)?.use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
        result.recycle()
        return uri
    }
}
