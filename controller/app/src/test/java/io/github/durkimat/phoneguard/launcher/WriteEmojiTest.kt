package io.github.durkimat.phoneguard.launcher

import io.github.durkimat.media.EmojiSet
import io.github.durkimat.media.Sticker
import io.github.durkimat.wacore.WaReaction
import org.junit.Assert.*
import org.junit.Test

/** Writing with emoji and stickers: the buttons under the text, editing around an emoji, and reactions on a message's page. */
class WriteEmojiTest {
    private val family = EmojiSet.decode("1F468+200D+1F469+200D+1F467"); private val thumb = EmojiSet.decode("1F44D+1F3FD")

    private fun chat(whatsApp: Boolean, voice: Boolean = false): LauncherModel {
        val m = LauncherModel(listOf(MenuItem("com.android.messaging", "Messages")), messagesApp = "com.android.messaging")
        m.voiceOn = voice; m.waEnabled = true; m.waLinked = true
        val c = listOf(Convo(if (whatsApp) "gFamily" else "0222", "Family", listOf(Msg("hi", 1_000, false, "Mum", "M1")), 0))
        if (whatsApp) { m.waConvos = c; m.badges = listOf(Badge(BadgeKind.WHATSAPP, 1)) } else { m.convos = c; m.badges = listOf(Badge(BadgeKind.SMS, 1)) }
        m.tap(240f, 500f); m.onKey(Key.OK); m.onKey(Key.UP); m.onKey(Key.OK) // the chat, then Reply
        assertEquals(Screen.WRITE, m.screen)
        return m
    }

    @Test fun downReachesTheButtonsAndStickersAreOnlyForWhatsApp() {
        assertEquals(listOf(WriteButton.EMOJI, WriteButton.STICKER), chat(true).writeButtons)
        assertEquals(listOf(WriteButton.EMOJI), chat(false).writeButtons)
        assertEquals(listOf(WriteButton.MIC, WriteButton.EMOJI, WriteButton.STICKER), chat(true, voice = true).writeButtons)
    }

    @Test fun leftAndRightMoveAlongTheButtonsAndOkOpensThePicker() {
        val m = chat(true, voice = true)
        m.onKey(Key.DOWN); assertEquals(WriteButton.MIC, m.writeButton); assertEquals("Speak" to "Back", m.softKeys) // the microphone is still one press away
        m.onKey(Key.RIGHT); assertEquals("Emoji" to "Back", m.softKeys); assertEquals(Action.PickEmoji, m.onKey(Key.OK))
        m.onKey(Key.DOWN); m.onKey(Key.RIGHT); m.onKey(Key.RIGHT); m.onKey(Key.RIGHT); assertEquals(WriteButton.STICKER, m.writeButton); assertEquals(Action.PickSticker, m.onKey(Key.SOFT_LEFT))
        m.onKey(Key.DOWN); m.onKey(Key.UP); assertEquals(1, m.writeField); assertNull(m.writeButton)
        m.onKey(Key.DOWN); assertEquals(WriteButton.MIC, m.writeButton) // and starts at the left again
    }

    @Test fun leavingAPickerWithoutChoosingStillLeavesSendThere() {
        for ((rights, action) in listOf(0 to Action.PickEmoji, 1 to Action.PickSticker)) {
            val m = chat(true); m.onKey(Key.DIGIT, 4)
            m.onKey(Key.DOWN); repeat(rights) { m.onKey(Key.RIGHT) }
            assertEquals(action, m.onKey(Key.OK)) // the picker opens over this screen; nothing is chosen there
            assertEquals(1, m.writeField); assertNull(m.writeButton); assertEquals("Send" to "Clear", m.softKeys)
        }
    }

    @Test fun onTheButtonRowTheRightKeyIsCalledBackAndLosesNothing() {
        val m = chat(true); m.onKey(Key.DIGIT, 4); val typed = m.writeText
        m.onKey(Key.DOWN); assertEquals("Back", m.softKeys.second)
        m.onKey(Key.SOFT_RIGHT); assertEquals(typed, m.writeText); assertEquals("Send" to "Clear", m.softKeys); assertEquals(Screen.WRITE, m.screen)
    }

    @Test fun anEmojiGoesInAtTheCursorAndIsDeletedAndSteppedOverWhole() {
        val m = chat(true)
        m.insertEmoji(family); m.insertEmoji(thumb)
        assertEquals(family + thumb, m.writeText); assertEquals(m.writeText.length, m.cursor)
        m.onKey(Key.LEFT); assertEquals(family.length, m.cursor); m.onKey(Key.LEFT); assertEquals(0, m.cursor); m.onKey(Key.RIGHT); assertEquals(family.length, m.cursor)
        m.onKey(Key.SOFT_RIGHT); assertEquals(thumb, m.writeText); assertEquals(0, m.cursor)
    }

    @Test fun theLimitNeverCutsAnEmojiInHalf() {
        val m = chat(true); repeat(100) { m.insertEmoji(family) }
        assertTrue(m.writeText.length <= 480); assertEquals(0, m.writeText.length % family.length)
    }

    @Test fun aStickerClosesAnEmptyMessageButKeepsTypedWords() {
        val a = chat(true); assertEquals("gFamily", a.stickerChosen()); assertEquals(Screen.CHAT, a.screen)
        val b = chat(true); b.insertEmoji(thumb); assertEquals("gFamily", b.stickerChosen()); assertEquals(Screen.WRITE, b.screen); assertEquals(thumb, b.writeText)
        assertNull(chat(false).stickerChosen()) // never by text message
        assertNull(LauncherModel(emptyList()).stickerChosen())
    }

    @Test fun reactionsAreShownUnderTheMessageCounted() {
        val m = LauncherModel(emptyList())
        val liked = Msg("hi", 1_000, false, "Mum", "M1", reactions = listOf(WaReaction("1", "Mum", thumb), WaReaction("2", "Nan", thumb), WaReaction("3", "Me", family)))
        assertEquals(listOf("hi", "$thumb 2  $family"), m.messageLines(liked))
        assertEquals(listOf("This message was deleted"), m.messageLines(liked.copy(body = "", deleted = true)))
    }
}
