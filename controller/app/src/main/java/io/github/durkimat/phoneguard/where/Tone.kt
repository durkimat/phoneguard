package io.github.durkimat.phoneguard.where

/**
 * Turns a colour map tile into four tones for the LCD: how much ink to lay over the paper, from none to a light grey, so the route drawn on top in full
 * ink stays the darkest thing on the screen. White (roads) is bare paper, plain land a faint tint, buildings, water and parks a mid tint, outlines and
 * text the darkest tint. Pure, tested without a phone.
 */
object Tone {
    private val INK = intArrayOf(0, 22, 55, 100) // alpha of the ink for each tone

    fun luminance(argb: Int): Int = (299 * ((argb shr 16) and 255) + 587 * ((argb shr 8) and 255) + 114 * (argb and 255)) / 1000

    fun level(lum: Int): Int = when { lum >= 249 -> 0; lum >= 228 -> 1; lum >= 200 -> 2; else -> 3 }

    /** One byte per pixel, the ink alpha (an ALPHA_8 bitmap), for the tile's ARGB pixels. */
    fun ink(pixels: IntArray): ByteArray = ByteArray(pixels.size) { INK[level(luminance(pixels[it]))].toByte() }
}
