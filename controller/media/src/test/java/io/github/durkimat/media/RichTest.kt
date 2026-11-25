package io.github.durkimat.media

import io.github.durkimat.wacore.WaMsg
import io.github.durkimat.wacore.WaReaction
import org.junit.Assert.*
import org.junit.Test

class RichTest {
    private fun msg(body: String = "hi", mine: Boolean = true, mid: String = "A1", kind: String = "text", date: Long = 1_000_000, deleted: Boolean = false) =
        WaMsg(body, date, mine, "", mid, kind, deleted = deleted)

    @Test fun onlyOwnRecentTextCanBeEdited() {
        val now = 1_000_000L + 10 * 60_000
        assertTrue(Rich.canEdit(msg(), now))
        assertFalse(Rich.canEdit(msg(), 1_000_000L + 16 * 60_000)) // too late
        assertFalse(Rich.canEdit(msg(mine = false), now)) // someone else's
        assertFalse(Rich.canEdit(msg(mid = ""), now)) // not known to be ours
        assertFalse(Rich.canEdit(msg(kind = "image"), now)); assertFalse(Rich.canEdit(msg(deleted = true), now))
    }

    @Test fun onlyOwnMessagesCanBeDeletedForEveryone() {
        assertTrue(Rich.canDelete(msg())); assertFalse(Rich.canDelete(msg(mine = false))); assertFalse(Rich.canDelete(msg(mid = ""))); assertFalse(Rich.canDelete(msg(deleted = true)))
    }

    @Test fun captionsLoseTheMarkerAndSnippetsKeepIt() {
        assertEquals("look", Rich.caption(msg("[photo] look", kind = "image")))
        assertEquals("", Rich.caption(msg("[sticker]", kind = "sticker")))
        assertEquals("[photo] look", Rich.snippet(msg("[photo] look", kind = "image")))
        assertEquals("[sticker]", Rich.snippet(msg("[sticker]", kind = "sticker")))
        assertEquals("plain [text]", Rich.caption(msg("plain [text]")))
        assertEquals("This message was deleted", Rich.snippet(msg("", deleted = true)))
        assertEquals(200, Rich.snippet(msg("x".repeat(500))).length)
    }

    @Test fun reactionsAreCountedMostUsedFirst() {
        val r = listOf("a", "b", "c", "d").mapIndexed { i, s -> WaReaction(s, s, if (i == 1) "❤" else "👍") }
        assertEquals(listOf("👍" to 3, "❤" to 1), Rich.groupReactions(r))
        assertTrue(Rich.groupReactions(emptyList()).isEmpty())
    }

    @Test fun sizesAndDurations() {
        assertEquals("4.2 MB", Rich.size((4.2 * 1024 * 1024).toLong())); assertEquals("512 KB", Rich.size(512 * 1024)); assertEquals("9 B", Rich.size(9))
        assertEquals("0:24", Rich.duration(24)); assertEquals("5:00", Rich.duration(300))
    }

    @Test fun picturesAreScaledDownNeverUp() {
        assertEquals(1600 to 1200, Rich.fit(4000, 3000)); assertEquals(900 to 1600, Rich.fit(1800, 3200)); assertEquals(800 to 600, Rich.fit(800, 600))
        assertEquals(1600 to 1, Rich.fit(8000, 1))
    }

    @Test fun videosOverTheLimitsAreRefused() {
        assertNull(Rich.videoRefusal(300, Rich.MAX_VIDEO_BYTES)); assertNotNull(Rich.videoRefusal(301, 1)); assertNotNull(Rich.videoRefusal(10, Rich.MAX_VIDEO_BYTES + 1))
    }

    @Test fun sendErrorsAreInPlainWords() {
        assertEquals("Sending too fast. Try again in a minute.", Rich.sendError(Exception("sending too fast: try again later")))
        assertEquals("Could not send. Try again.", Rich.sendError(Exception("boom")))
    }
}

class VideoScaleTest {
    @Test fun smallVideosAreLeftAlone() { assertNull(Rich.videoScale(1280, 720, 0)); assertNull(Rich.videoScale(720, 1280, 0)); assertNull(Rich.videoScale(0, 0, 0)) }
    @Test fun landscapeScalesByHeightAndPortraitByWidth() {
        assertEquals(false to 720, Rich.videoScale(1920, 1080, 0)) // landscape: fix the height
        assertEquals(true to 720, Rich.videoScale(1080, 1920, 0)) // portrait: fix the width
    }
    @Test fun aStoredRotationSwapsTheSides() {
        assertEquals(true to 720, Rich.videoScale(1920, 1080, 90)) // stored sideways, shown upright
        assertEquals(false to 720, Rich.videoScale(1920, 1080, 180))
    }
}

class BubbleTest {
    @Test fun aBubbleIsAtMost78PercentOfTheWidth() {
        assertEquals(280, Rich.bubbleCap(360)); assertEquals(0, Rich.bubbleCap(0)); assertEquals(624, Rich.bubbleCap(800)) // phone and landscape
    }
    @Test fun callNotesAreSystemLinesAndNothingElseIs() {
        fun m(b: String, k: String = "text") = io.github.durkimat.wacore.WaMsg(b, 0, false, "", "x", k)
        assertTrue(Rich.isSystem(m("[call declined]"))); assertTrue(Rich.isSystem(m("[missed call]")))
        assertFalse(Rich.isSystem(m("[photo] hi", "image"))); assertFalse(Rich.isSystem(m("call me")))
    }
}

class ColumnsTest {
    // the grid's own width: the screen minus the tray's side padding (24 dp)
    @Test fun aPhoneGetsFourColumns() { assertEquals(4, Rich.columns(360f - 24)); assertEquals(4, Rich.columns(412f - 24)) }
    @Test fun aWideScreenGetsMore() { assertEquals(9, Rich.columns(800f - 24)); assertEquals(14, Rich.columns(1280f - 24)); assertTrue(Rich.columns(800f) >= 8 && Rich.columns(1280f) >= 8) }
    @Test fun neverFewerThanTheMinimumAndTilesStayAboutEightyFourDp() {
        assertEquals(4, Rich.columns(100f)); assertEquals(3, Rich.columns(100f, 100f, 3)); assertEquals(3, Rich.columns(336f, 100f, 3)); assertEquals(7, Rich.columns(776f, 100f, 3))
        for (w in listOf(360f, 412f, 800f, 1280f)) { val cols = Rich.columns(w); assertTrue(w / cols in 84f..(84f * 1.3f + 1) || cols == 4) }
    }
}
