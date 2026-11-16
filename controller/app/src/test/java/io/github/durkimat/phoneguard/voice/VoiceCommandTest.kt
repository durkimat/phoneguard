package io.github.durkimat.phoneguard.voice

import io.github.durkimat.phoneguard.voice.VoiceCommand.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandTest {
    @Test fun theFourIntentsParse() {
        assertEquals(Call("Granny"), VoiceCommand.parse("""{"intent":"call","args":{"name":" Granny "},"confirm":true}"""))
        assertEquals(Play("my study playlist", "playlist"), VoiceCommand.parse("""{"intent":"play","args":{"query":"my study playlist","kind":"playlist"}}"""))
        assertEquals(Timer(10), VoiceCommand.parse("""{"intent":"timer","args":{"minutes":10}}"""))
        assertEquals(Navigate("Hexham Station"), VoiceCommand.parse("""{"intent":"navigate","args":{"destination":"Hexham Station"}}"""))
    }

    @Test fun anythingElseIsUnknown() {
        assertEquals(Unknown(), VoiceCommand.parse(null))
        assertEquals(Unknown(), VoiceCommand.parse("garbage"))
        assertEquals(Unknown("Sorry, I didn't catch that"), VoiceCommand.parse("""{"intent":"unknown","args":{},"say":"Sorry, I didn't catch that"}"""))
        assertEquals(Unknown(), VoiceCommand.parse("""{"intent":"set_mode","args":{"mode":"day"}}"""))
        assertEquals(Unknown(), VoiceCommand.parse("""{"intent":"timer","args":{"minutes":0}}"""))
        assertEquals(Unknown(), VoiceCommand.parse("""{"intent":"timer","args":{"minutes":601}}"""))
        assertEquals(Unknown(), VoiceCommand.parse("""{"intent":"call","args":{"name":"  "}}"""))
    }

    @Test fun playKindDefaultsToAnyAndLengthsAreCapped() {
        assertEquals(Play("x", "any"), VoiceCommand.parse("""{"intent":"play","args":{"query":"x","kind":"podcast"}}"""))
        assertEquals(120, (VoiceCommand.parse("""{"intent":"play","args":{"query":"${"a".repeat(300)}"}}""") as Play).query.length)
    }

    @Test fun namesMatchFuzzily() {
        val names = listOf("Granny Annie", "Granny", "Dad", "Mum mobile", "Auntie Jo")
        assertEquals(1, NameMatch.best(names, "granny"))        // the exact name beats the longer one
        assertEquals(0, NameMatch.best(names, "Granny Annie"))
        assertEquals(3, NameMatch.best(names, "mum"))
        assertEquals(4, NameMatch.best(names, "jo"))
        assertEquals(2, NameMatch.best(names, "call dad please")) // the contact's name is inside what was said
        assertNull(NameMatch.best(names, "Zebedee"))
        assertNull(NameMatch.best(emptyList(), "dad"))
    }
}
