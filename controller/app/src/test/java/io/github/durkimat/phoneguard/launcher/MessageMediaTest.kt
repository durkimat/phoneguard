package io.github.durkimat.phoneguard.launcher

import io.github.durkimat.media.Rich
import io.github.durkimat.phoneguard.gallery.GAction
import io.github.durkimat.phoneguard.gallery.GItem
import io.github.durkimat.phoneguard.gallery.GalleryModel
import org.junit.Assert.*
import org.junit.Test

/** Pictures, stickers, GIFs and videos on a message's page: what is drawn, what OK does, and that replying and Back are unchanged. */
class MessageMediaTest {
    private fun msg(body: String, kind: String = "text", path: String = "", ref: String = "", mid: String = "M1", size: Long = 0, seconds: Int = 0, deleted: Boolean = false) =
        Msg(body, 1_000L, false, "Mum", mid, kind, path, ref, size, "", seconds, deleted = deleted)

    private val here = msg("[photo] look at this", "image", path = "/m/a.jpg", ref = "r")
    private val away = msg("[video]", "video", ref = "r", mid = "M2", size = 3L * 1024 * 1024, seconds = 24)

    /** The model on the page of [m]: Menu > Messages > WhatsApp > the chat > the message. */
    private fun page(m: Msg): LauncherModel {
        val model = LauncherModel(listOf(MenuItem("com.android.messaging", "Messages")), messagesApp = "com.android.messaging")
        model.waEnabled = true; model.waLinked = true
        model.waConvos = listOf(Convo("gFamily", "Family", listOf(m), 0))
        model.mediaExists = { it == "/m/a.jpg" }
        model.openInbox(); model.goHome()
        model.badges = listOf(Badge(BadgeKind.WHATSAPP, 1)); model.tap(240f, 500f)
        if (model.screen != Screen.WHATSAPP) { model.goHome(); model.onKey(Key.OK); model.onKey(Key.OK); model.onKey(Key.DIGIT, 2) }
        model.onKey(Key.OK) // the chat, on its newest message
        model.onKey(Key.OK) // its page
        assertEquals(Screen.THREAD, model.screen)
        return model
    }

    @Test fun theStateOfAMessagesMedia() {
        val has: (String) -> Boolean = { true }; val hasNot: (String) -> Boolean = { false }
        assertEquals(Rich.Picture.READY, Rich.picture(here, has))
        assertEquals(Rich.Picture.FETCH, Rich.picture(here, hasNot)) // the file was cleared away: it can be asked for again
        assertEquals(Rich.Picture.FETCH, Rich.picture(away, has))
        assertEquals(Rich.Picture.NONE, Rich.picture(msg("hi"), has))
        assertEquals(Rich.Picture.NONE, Rich.picture(msg("[photo]", "image"), has)) // an old message with nothing kept: the marker stays
        assertEquals(Rich.Picture.NONE, Rich.picture(here.copy(deleted = true), has))
    }

    @Test fun aPictureTakesTheTopOfThePageAndItsCaptionFollowsWithoutTheMarker() {
        val m = page(here)
        val lines = m.messageLines(here)
        assertEquals(PICTURE_LINES + 1, lines.size); assertTrue(lines.take(PICTURE_LINES).all { it.isEmpty() }); assertEquals("look at this", lines.last())
        assertEquals(PICTURE_LINES, m.messageLines(away).size) // no caption
        assertEquals(listOf("[photo]"), m.messageLines(msg("[photo]", "image")))
        assertEquals(listOf("This message was deleted"), m.messageLines(here.copy(body = "", deleted = true)))
    }

    @Test fun okViewsAPictureThatIsHere() {
        val m = page(here)
        assertEquals("OK: view", m.pictureHint)
        assertEquals(Action.WaViewMedia("/m/a.jpg", "image", false, 0, "Mum", "look at this"), m.onKey(Key.OK))
        assertEquals(Screen.THREAD, m.screen) // the viewer is on top; Back from it is back on the message
    }

    @Test fun okAsksForMediaThatIsNotHereAndWaitsWhileItComes() {
        val m = page(away)
        assertEquals("OK: get video 0:24 (3.0 MB)", m.pictureHint)
        assertEquals(Action.WaGetMedia("gFamily", "M2"), m.onKey(Key.OK))
        m.waFetching = setOf("M2")
        assertEquals("Getting...", m.pictureHint); assertEquals(Action.None, m.onKey(Key.OK))
    }

    @Test fun theLeftKeyStillRepliesAndOkRepliesToPlainText() {
        val a = page(here); a.onKey(Key.SOFT_LEFT); assertEquals(Screen.WRITE, a.screen)
        val b = page(msg("hello")); assertNull(b.pictureHint); b.onKey(Key.OK); assertEquals(Screen.WRITE, b.screen)
    }

    @Test fun backFromAPictureMessageRetracesToTheChat() {
        val m = page(here); m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.CHAT, m.screen)
    }

    @Test fun theChatListSaysWhenAMessageWasDeleted() {
        val m = page(here.copy(body = "", deleted = true)); m.onKey(Key.SOFT_RIGHT)
        assertTrue(m.rows[1].endsWith("(deleted)"))
    }

    @Test fun aDeletionShowsAtOnceOnTheOpenMessage() {
        val m = page(here)
        m.refreshOpenChat(listOf(Convo("gFamily", "Family", listOf(here.copy(body = "", mediaPath = "", mediaRef = "", deleted = true)), 0)))
        assertEquals(listOf("This message was deleted"), m.messageLines(m.shownMsg!!)); assertNull(m.pictureHint)
        m.onKey(Key.SOFT_RIGHT); assertTrue(m.rows[1].endsWith("(deleted)")) // and in the list behind it
    }

    @Test fun fetchedMediaAppearsOnTheOpenMessage() {
        val m = page(away); assertTrue(m.pictureHint!!.startsWith("OK: get"))
        m.mediaExists = { true }
        m.refreshOpenChat(listOf(Convo("gFamily", "Family", listOf(away.copy(mediaPath = "/m/b.mp4")), 0)))
        assertEquals("OK: play 0:24", m.pictureHint)
    }

    @Test fun aNewMessageDoesNotMoveWhatIsBeingRead() {
        val m = page(here)
        m.refreshOpenChat(listOf(Convo("gFamily", "Family", listOf(here, msg("newer", mid = "M9")), 1)))
        assertEquals(1, m.openConvo!!.msgs.size); assertEquals(here, m.shownMsg)
    }

    @Test fun theFullViewOfOneMessageHasNoGridBehindIt() {
        val g = GalleryModel(); g.showOne(GItem("/m/a.jpg", false, "look", 0, ""), "Mum")
        assertEquals("", g.viewerTitle); assertEquals("Colour", g.leftLabel)
        g.onKey(Key.RIGHT, -1); g.onKey(Key.DOWN, -1); assertEquals(0, g.sel)
        assertEquals(GAction.Exit, g.onKey(Key.SOFT_RIGHT, -1))
    }
}
