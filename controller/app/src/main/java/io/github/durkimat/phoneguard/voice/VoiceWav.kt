package io.github.durkimat.phoneguard.voice

import java.io.ByteArrayOutputStream

/** The sound the phone sends for voice input: 16 kHz, mono, 16-bit WAV (what the Worker's contract asks for). Pure, tested without a phone. */
object VoiceWav {
    const val RATE = 16_000
    const val BYTES_PER_SECOND = RATE * 2
    /** The Worker refuses more than 15 s or 500 KB; 15 s of this format is 480,044 bytes with the header. */
    const val MAX_DICTATION_MS = 15_000
    /** Shorter than this is a tap, not speech. */
    const val MIN_MS = 500

    fun millis(pcmBytes: Int) = pcmBytes * 1000L / BYTES_PER_SECOND

    fun wav(pcm: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(pcm.size + 44)
        fun i32(v: Int) { o.write(v and 255); o.write(v shr 8 and 255); o.write(v shr 16 and 255); o.write(v shr 24 and 255) }
        fun i16(v: Int) { o.write(v and 255); o.write(v shr 8 and 255) }
        o.write("RIFF".toByteArray()); i32(36 + pcm.size); o.write("WAVE".toByteArray())
        o.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(RATE); i32(BYTES_PER_SECOND); i16(2); i16(16)
        o.write("data".toByteArray()); i32(pcm.size); o.write(pcm)
        return o.toByteArray()
    }

    /** How loud the last chunk was, 0 (quiet) to 4, for the level bars on the screen. */
    fun level(pcm: ByteArray, len: Int): Int {
        val n = len / 2
        if (n == 0) return 0
        var sum = 0.0
        for (k in 0 until n) { val s = (pcm[2 * k].toInt() and 255) or (pcm[2 * k + 1].toInt() shl 8); sum += s.toDouble() * s }
        val rms = Math.sqrt(sum / n)
        return when { rms < 150 -> 0; rms < 600 -> 1; rms < 1800 -> 2; rms < 4500 -> 3; else -> 4 }
    }
}
