package io.github.durkimat.familychat

import android.content.Context

/** The sticker packs shipped inside the app (512x512 WebP, our own artwork). */
object Stickers {
    data class Pack(val id: String, val name: String)
    val packs = listOf(Pack("happy", "Happy"), Pack("words", "Words"), Pack("animals", "Animals"))

    /** The asset paths of a pack's stickers, in order. */
    fun list(c: Context, pack: String): List<String> = (c.assets.list("stickers/$pack") ?: emptyArray()).sorted().map { "stickers/$pack/$it" }
}
