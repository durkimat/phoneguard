package io.github.durkimat.phoneguard.voice

import org.json.JSONObject

/**
 * A spoken command as the Worker returns it (docs/voice-protocol.md), checked again on the phone: only these shapes are ever acted on, strings are cut to
 * the contract's lengths, anything else is [Unknown]. The phone still applies the mode's own rules when it acts. Pure, tested without a phone.
 */
sealed class VoiceCommand {
    data class Call(val name: String) : VoiceCommand()
    data class Play(val query: String, val kind: String) : VoiceCommand()
    data class Timer(val minutes: Int) : VoiceCommand()
    data class Navigate(val destination: String) : VoiceCommand()
    data class Unknown(val say: String = "Sorry, I didn't catch that") : VoiceCommand()

    companion object {
        fun parse(json: String?): VoiceCommand {
            val j = try { JSONObject(json ?: return Unknown()) } catch (_: Exception) { return Unknown() }
            val a = j.optJSONObject("args") ?: JSONObject()
            fun text(k: String, max: Int) = a.optString(k).trim().take(max)
            return when (j.optString("intent")) {
                "call" -> text("name", 60).let { if (it.isEmpty()) Unknown() else Call(it) }
                "play" -> text("query", 120).let { q -> if (q.isEmpty()) Unknown() else Play(q, text("kind", 10).takeIf { it in setOf("playlist", "artist", "song", "any") } ?: "any") }
                "timer" -> a.optInt("minutes", 0).let { if (it in 1..600) Timer(it) else Unknown() }
                "navigate" -> text("destination", 120).let { if (it.isEmpty()) Unknown() else Navigate(it) }
                else -> Unknown(j.optString("say").ifBlank { "Sorry, I didn't catch that" })
            }
        }
    }
}

/** Finds the contact or playlist a spoken name most likely means (names are kept exactly as said, so this is fuzzy). */
object NameMatch {
    private fun words(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(' ').filter { it.isNotEmpty() }

    private fun score(name: String, spoken: String): Int {
        val n = words(name); val s = words(spoken)
        if (n.isEmpty() || s.isEmpty()) return 0
        val nn = n.joinToString(" "); val ss = s.joinToString(" ")
        return when {
            nn == ss -> 100
            s.all { it in n } -> 80
            nn.startsWith(ss) -> 70
            nn.contains(ss) -> 50
            n.all { it in s } -> 40
            else -> 0
        }
    }

    /** The index of the best match, the shortest name winning a tie; null when nothing matches. */
    fun best(names: List<String>, spoken: String): Int? =
        names.withIndex().map { Triple(it.index, score(it.value, spoken), it.value.length) }.filter { it.second > 0 }.sortedWith(compareByDescending<Triple<Int, Int, Int>> { it.second }.thenBy { it.third }).firstOrNull()?.first
}
