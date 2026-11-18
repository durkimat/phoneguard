package io.github.durkimat.phoneguard.alarm

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The phone's own short tunes for alarms (and later ringtones): monophonic RTTTL strings drawn as a square wave, so there are no sound
 * files. Every melody is public domain or plain beeps; "Classic waltz" is Tarrega's Gran Vals (1902), not any company's recording.
 */
object Tones {
    class Tone(val id: String, val name: String, val rtttl: String)

    private fun rep(s: String, n: Int) = (1..n).joinToString(",") { s }

    val all = listOf(
        Tone("waltz", "Classic waltz", "waltz:d=4,o=5,b=225:8e6,8d6,f#,g#,8c#6,8b,d,e,8b,8a,c#,e,2a"),
        Tone("elise", "Fur Elise", "elise:d=8,o=5,b=140:e6,d#6,e6,d#6,e6,b,d6,c6,4a,p,c,e,a,4b,p,e,g#,b,4c6,p,e,e6,d#6,e6,d#6,e6,b,d6,c6,4a"),
        Tone("greensleeves", "Greensleeves", "greensleeves:d=4,o=5,b=150:8a,2c6,d6,e.6,8f6,e6,2d6,b,g.,8a,b,2c6,a,a.,8g#,a,2b,g#,2e"),
        Tone("ode", "Ode to Joy", "ode:d=4,o=5,b=150:e,e,f,g,g,f,e,d,c,c,d,e,e.,8d,2d"),
        Tone("ring", "Ring-ring", "ring:d=32,o=6,b=200:" + rep("a,e7", 8) + ",8p," + rep("a,e7", 8) + ",4p,2p"),
        Tone("beep", "Beep-beep", "beep:d=8,o=6,b=180:c7,p,c7,p,c7,p,c7,2p"),
        Tone("chime", "Rising chime", "chime:d=8,o=5,b=130:c,e,g,c6,4e6,2p"),
        Tone("morning", "Morning", "morning:d=4,o=5,b=110:c,e,g,2c6,p,g,e,2c,p"),
        // short message-style blips
        Tone("sms", "Text beep-beep", "sms:d=16,o=6,b=200:e7,p,e7,4p"),
        Tone("pair", "Two-note chime", "pair:d=8,o=6,b=150:g,4e7,2p"),
        Tone("pop", "Soft pop", "pop:d=32,o=6,b=200:c,g,c7,4p"),
        Tone("double", "Double beep", "double:d=8,o=6,b=170:a,p,a,2p"),
    )
    const val DEFAULT = "chime"
    fun byId(id: String): Tone = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT }

    class Note(val hz: Double, val ms: Int)

    private val SEMI = mapOf("c" to 0, "c#" to 1, "d" to 2, "d#" to 3, "e" to 4, "f" to 5, "f#" to 6, "g" to 7, "g#" to 8, "a" to 9, "a#" to 10, "b" to 11)

    /** Reads an RTTTL string: `name:d=4,o=5,b=63:8a4,8p,...` (a pause is hz 0). Bad notes are skipped. */
    fun parse(rtttl: String): List<Note> {
        val parts = rtttl.split(':')
        if (parts.size < 3) return emptyList()
        var d = 4; var o = 6; var b = 63
        for (kv in parts[1].split(',')) {
            val p = kv.trim().split('=')
            if (p.size != 2) continue
            val v = p[1].trim().toIntOrNull() ?: continue
            when (p[0].trim()) { "d" -> d = v; "o" -> o = v; "b" -> b = v }
        }
        val whole = 240_000.0 / b.coerceAtLeast(1)
        val out = ArrayList<Note>()
        val re = Regex("^(\\d+)?([a-gp]#?)(\\.)?(\\d)?(\\.)?$")
        for (raw in parts[2].split(',')) {
            val m = re.matchEntire(raw.trim().lowercase()) ?: continue
            val len = m.groupValues[1].toIntOrNull() ?: d
            if (len <= 0) continue
            var ms = whole / len
            if (m.groupValues[3].isNotEmpty() || m.groupValues[5].isNotEmpty()) ms *= 1.5
            val name = m.groupValues[2]
            val oct = m.groupValues[4].toIntOrNull() ?: o
            val hz = if (name == "p") 0.0 else 440.0 * 2.0.pow(((oct - 4) * 12 + (SEMI[name] ?: continue) - 9) / 12.0)
            out.add(Note(hz, ms.toInt()))
        }
        return out
    }

    fun lengthMs(t: Tone) = parse(t.rtttl).sumOf { it.ms }

    /** The tune as 16-bit mono PCM: a soft square wave with a short fade at each note's ends, and a short gap after each note. */
    fun render(t: Tone, rate: Int = 16000): ShortArray {
        val notes = parse(t.rtttl)
        val total = notes.sumOf { it.ms.toLong() * rate / 1000 }.toInt()
        val pcm = ShortArray(total)
        var at = 0
        for (n in notes) {
            val count = (n.ms.toLong() * rate / 1000).toInt()
            if (n.hz > 0) {
                val fade = (rate / 200).coerceAtMost(count / 2).coerceAtLeast(1)
                val gap = if (count > rate / 20) rate / 100 else 0 // a breath between repeated notes
                for (k in 0 until count - gap) {
                    val sq = if (sin(2 * PI * n.hz * k / rate) >= 0) 1.0 else -1.0
                    val env = minOf(1.0, k / fade.toDouble(), (count - gap - k) / fade.toDouble())
                    pcm[at + k] = (sq * env * 0.45 * Short.MAX_VALUE).toInt().toShort()
                }
            }
            at += count
        }
        return pcm
    }
}
