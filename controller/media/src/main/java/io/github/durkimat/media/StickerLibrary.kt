package io.github.durkimat.media

import android.content.Context
import org.json.JSONObject

/** One sticker of the tray. [asset] is its path in the app's assets. */
data class Sticker(val id: String, val name: String, val keywords: String, val cat: String, val animated: Boolean, val asset: String)

/**
 * The sticker library the app ships: about 370 static stickers (Microsoft Fluent Emoji, MIT) and about 50 animated ones (Google's Noto animated
 * emoji, CC BY 4.0), made by stickers/build-stickers.py into assets/stickers-lib, plus our own three packs. Names and keywords are the Unicode CLDR
 * ones that come with Fluent's data. The tray has these tabs: [TABS].
 */
object StickerLibrary {
    val TABS = listOf("Recent", "Animated", "Happy", "Love", "Animals", "Food", "Sports", "Party", "Weather", "Words")
    const val RECENT_MAX = 24

    private var cached: List<Sticker>? = null

    fun all(c: Context): List<Sticker> = cached ?: synchronized(this) {
        cached ?: (try { parse(c.assets.open("stickers-lib/index.json").bufferedReader().use { it.readText() }) } catch (_: Throwable) { emptyList() } + own(c)).also { cached = it }
    }

    /** The library's index.json. */
    fun parse(json: String): List<Sticker> {
        val a = JSONObject(json).getJSONArray("items")
        return (0 until a.length()).map { val o = a.getJSONObject(it); Sticker(o.getString("id"), o.getString("name"), o.optString("k"), o.getString("cat"), o.optBoolean("anim"), "stickers-lib/" + o.getString("file")) }
    }

    private val ownWords = listOf("yes", "no", "ok", "thanks", "hi", "bye", "sorry", "love", "lol", "wow", "yay", "help")
    private val ownHappy = listOf("happy smile", "laugh", "wink", "cool sunglasses", "love heart eyes", "sleepy", "surprised", "sad", "red heart", "star", "sun", "flower")
    private val ownAnimals = listOf("cat", "dog", "rabbit", "bear", "fox", "frog", "owl", "pig", "mouse", "chick", "panda", "fish")

    /** Our own three packs (our own artwork, kept from before): Words is a tab of its own, the others join Happy and Animals. */
    fun own(c: Context): List<Sticker> =
        ownWords.mapIndexed { i, n -> Sticker("own-words-${i + 1}", n, "word text", "Words", false, "stickers/words/%02d.webp".format(i + 1)) } +
        ownHappy.mapIndexed { i, n -> Sticker("own-happy-${i + 1}", n, "face smiley", "Happy", false, "stickers/happy/%02d.webp".format(i + 1)) } +
        ownAnimals.mapIndexed { i, n -> Sticker("own-animals-${i + 1}", n, "animal", "Animals", false, "stickers/animals/%02d.webp".format(i + 1)) }

    /** What a tab shows. "Recent" lists the ids in [recent] order. */
    fun tab(all: List<Sticker>, tab: String, recent: List<String>): List<Sticker> = when (tab) {
        "Recent" -> { val by = all.associateBy { it.id }; recent.mapNotNull { by[it] } }
        "Animated" -> all.filter { it.animated }
        else -> all.filter { !it.animated && it.cat == tab }
    }

    /** Every word the child types must be found in a sticker's name, keywords or tab name (case-insensitive); an empty search matches nothing here (the tab shows). */
    fun search(all: List<Sticker>, query: String): List<Sticker> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return all.filter { s -> val hay = (s.name + " " + s.keywords + " " + s.cat + if (s.animated) " animated animation" else "").lowercase(); words.all { it in hay } }
    }

    /** A new recent list: the sticker first, no repeat, at most [RECENT_MAX]. */
    fun pushRecent(recent: List<String>, id: String): List<String> = (listOf(id) + recent.filter { it != id }).take(RECENT_MAX)
}
