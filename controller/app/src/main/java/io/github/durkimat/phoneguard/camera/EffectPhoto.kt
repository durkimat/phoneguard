package io.github.durkimat.phoneguard.camera

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    /** Turn decoded pixels into the effect picture, at the effect's own grain. */
    fun render(px: IntArray, w: Int, h: Int, e: Effect, lcd: Int, ink: Int): Bitmap {
        val cell = Effects.cellFor(e)
        val cols = (w / cell).coerceAtLeast(1); val rows = (h / cell).coerceAtLeast(1)
        val cells = Dither.sample(w, h, 0, false, cols, rows) { x, y -> val c = px[y * w + x]; (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000 }
        val small = Bitmap.createBitmap(Effects.render(e, cells, cols, rows, lcd, ink, fine = true), cols, rows, Bitmap.Config.ARGB_8888)
        val scaled = if (cell == 1) small else Bitmap.createScaledBitmap(small, cols * cell, rows * cell, false).also { small.recycle() }
        return if (e == Effect.FRAME) framed(scaled, lcd, ink) else scaled
    }

    /** A border in the screen colour with the date and time under the picture, like an instant photo. */
    private fun framed(pic: Bitmap, lcd: Int, ink: Int): Bitmap {
        val side = pic.width / 18; val bottom = pic.width / 5
        val out = Bitmap.createBitmap(pic.width + 2 * side, pic.height + side + bottom, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(lcd or (0xFF shl 24))
        c.drawBitmap(pic, side.toFloat(), side.toFloat(), null)
        val line = Paint().apply { color = ink; style = Paint.Style.STROKE; strokeWidth = (side / 6f).coerceAtLeast(2f) }
        c.drawRect(side.toFloat(), side.toFloat(), (side + pic.width).toFloat(), (side + pic.height).toFloat(), line)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = Typeface.MONOSPACE; isFakeBoldText = true; textAlign = Paint.Align.CENTER; textSize = bottom * 0.34f }
        c.drawText(SimpleDateFormat("d MMM yyyy  HH:mm", Locale.UK).format(Date()).uppercase(), out.width / 2f, side + pic.height + bottom * 0.62f, text)
        pic.recycle()
        return out
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
