package io.github.durkimat.phoneguard.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTest {
    @Test fun wavHasAHeaderAndTheSamples() {
        val w = VoiceWav.wav(ByteArray(32000))
        assertEquals(32044, w.size)
        assertEquals("RIFF", String(w, 0, 4)); assertEquals("WAVE", String(w, 8, 4)); assertEquals("data", String(w, 36, 4))
        assertEquals(32000, (w[40].toInt() and 255) or (w[41].toInt() and 255 shl 8) or (w[42].toInt() and 255 shl 16))
        assertEquals(16000, (w[24].toInt() and 255) or (w[25].toInt() and 255 shl 8)) // sample rate
    }

    @Test fun fifteenSecondsFitsTheWorkersLimit() { assertTrue(VoiceWav.wav(ByteArray(VoiceWav.MAX_DICTATION_MS * VoiceWav.BYTES_PER_SECOND / 1000)).size < 500_000) }

    @Test fun millisAndLevels() {
        assertEquals(1000L, VoiceWav.millis(32000))
        assertEquals(0, VoiceWav.level(ByteArray(3200), 3200))
        val loud = ByteArray(3200) { if (it % 2 == 1) 0x40 else 0 } // samples of 0x4000 = 16384
        assertEquals(4, VoiceWav.level(loud, 3200))
    }

    @Test fun dictationJoinsWithOneSpace() {
        assertEquals("hello world" to 11, VoiceText.insert("hello", 5, " world "))
        assertEquals("hello" to 5, VoiceText.insert("hello", 5, "   "))
        assertEquals("Hexham" to 6, VoiceText.insert("", 0, "Hexham"))
        assertEquals("hello brave world" to 11, VoiceText.insert("hello world", 5, "brave"))
        assertEquals("go there." to 8, VoiceText.insert("go.", 2, "there"))
    }

    @Test fun repliesMapToPlainWords() {
        assertEquals(VoiceReply.Heard("Hawes", false), VoiceReply.parse(200, """{"text":" Hawes ","quality":"ok","seconds":1.2}"""))
        assertEquals(VoiceReply.Heard("x", true), VoiceReply.parse(200, """{"text":"x","quality":"low"}"""))
        assertEquals(VoiceReply.Nothing, VoiceReply.parse(200, """{"text":""}"""))
        assertEquals(VoiceReply.Refused("Voice is off in this mode"), VoiceReply.parse(403, """{"error":"voice_off"}"""))
        assertEquals(VoiceReply.Refused("That was too long"), VoiceReply.parse(413, """{"error":"too_long"}"""))
        assertEquals(VoiceReply.Refused("Voice is used up for today"), VoiceReply.parse(429, """{"error":"used_up"}"""))
        assertEquals(VoiceReply.Failed("Voice isn't working right now"), VoiceReply.parse(503, """{"error":"unavailable"}"""))
        assertEquals(VoiceReply.Failed("Voice isn't working right now"), VoiceReply.parse(500, "garbage"))
    }
}
