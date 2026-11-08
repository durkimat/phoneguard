package io.github.durkimat.phoneguard.whatsapp

import android.content.Context
import io.github.durkimat.phoneguard.launcher.LearnedWords
import java.io.File

/** Keeps [LearnedWords] in a small private file on the phone. */
object LearnedStore {
    @Volatile private var words: LearnedWords? = null

    private fun file(c: Context) = File(c.applicationContext.filesDir, "learned-words.tsv")

    @Synchronized fun get(c: Context): LearnedWords = words ?: run {
        val m = HashMap<String, Int>()
        try { file(c).forEachLine { l -> val p = l.split('\t'); if (p.size == 2) p[1].toIntOrNull()?.let { m[p[0]] = it } } } catch (_: Exception) { }
        LearnedWords(m).also { words = it }
    }

    /** Reads a message the owner has just sent. */
    fun learn(c: Context, text: String, isKnown: (String) -> Boolean = { true }) {
        val w = get(c)
        if (w.learnText(text, isKnown) > 0) save(c)
    }

    @Synchronized fun save(c: Context) {
        val w = words ?: return
        if (!w.changed) return
        try { file(c).writeText(w.snapshot().entries.joinToString("\n") { "${it.key}\t${it.value}" }); w.markSaved() } catch (_: Exception) { }
    }

    fun forget(c: Context) { get(c).forget(); try { file(c).delete() } catch (_: Exception) { } }
}
