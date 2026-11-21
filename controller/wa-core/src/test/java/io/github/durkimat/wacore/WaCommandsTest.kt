package io.github.durkimat.wacore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaCommandsTest {
    @Test fun theGrammarIsAddRemoveGroupsAndANumber() {
        assertEquals(WaCommand.AddGroup("Family Chat"), WaCommands.parse("  Add   group Family Chat "))
        assertEquals(WaCommand.RemoveGroup("Football"), WaCommands.parse("remove group Football"))
        assertEquals(WaCommand.ListAllowed, WaCommands.parse("Groups"))
        assertEquals(WaCommand.Choose(2), WaCommands.parse(" 2 "))
        for (bad in listOf("", "hello", "add group", "add group ", "remove group", "pin 1234", "1234")) assertEquals(bad, WaCommand.Unknown, WaCommands.parse(bad))
    }

    private val groups = "111|Family\n222|family\n333|Football\n444|FamilyChat-Tom"

    @Test fun aNameMatchesOneManyOrNoGroups() {
        assertEquals(WaMatch.One("333", "Football"), WaCommands.match("football", groups))
        assertEquals(WaMatch.None, WaCommands.match("Chess", groups))
        val many = WaCommands.match("FAMILY", groups) as WaMatch.Many
        assertEquals(listOf("111" to "Family", "222" to "family"), many.options)
    }

    @Test fun theAdminGroupIsNeverOffered() {
        assertEquals(WaMatch.None, WaCommands.match("FamilyChat-Tom", groups, exceptId = "444@g.us"))
    }

    @Test fun adminGroupsAreFoundByTheirPrefix() {
        assertEquals(listOf("444" to "FamilyChat-Tom"), WaCommands.adminCandidates(groups))
        assertEquals(emptyList<Pair<String, String>>(), WaCommands.adminCandidates("111|Family"))
    }

    @Test fun adminGroupNameComesFromOneConstant() {
        assertEquals("FamilyChat-", WaCommands.ADMIN_PREFIX)
        assertEquals("FamilyChat-Sam", WaCommands.adminGroupName(" Sam "))
        assertEquals("X-Sam", WaCommands.adminGroupName("Sam", "X-"))
        assertEquals(listOf("5" to "X-Sam"), WaCommands.adminCandidates("5|X-Sam\n6|FamilyChat-Sam", "X-"))
    }
}

class WaLabelsTest {
    @Test fun aStickerIsPrecededByTheSentByLine() {
        val plan = WaLabels.sticker("Sam", "/s.webp", true)
        assertEquals(listOf<WaLabels.Step>(WaLabels.Step.Text("Sent by Sam"), WaLabels.Step.Sticker("/s.webp", true)), plan)
    }

    @Test fun photosAndVideosCarryTheNameInTheCaption() {
        assertEquals("Sam: look", WaLabels.caption("Sam", " look "))
        assertEquals("Sam:", WaLabels.caption("Sam", "  "))
        assertEquals(listOf<WaLabels.Step>(WaLabels.Step.Photo("/p.jpg", "Sam:", 10, 20)), WaLabels.photo("Sam", "/p.jpg", "", 10, 20))
        assertEquals(listOf<WaLabels.Step>(WaLabels.Step.Video("/v.mp4", "Sam: hi", 3, 1, 2)), WaLabels.video("Sam", "/v.mp4", "hi", 3, 1, 2))
    }

    @Test fun withNoNameNothingIsAdded() {
        assertEquals(listOf<WaLabels.Step>(WaLabels.Step.Sticker("/s.webp", false)), WaLabels.sticker(" ", "/s.webp", false))
        assertEquals("hi", WaLabels.caption("", "hi"))
    }

    @Test fun photosCommandsAndTheirReplies() {
        assertEquals(WaCommand.Photos(true), WaCommands.parse(" Photos  ON "))
        assertEquals(WaCommand.Photos(false), WaCommands.parse("photos off"))
        assertEquals(WaCommand.Unknown, WaCommands.parse("photos"))
        assertEquals(WaCommand.Unknown, WaCommands.parse("photos maybe"))
        assertEquals("Photos and videos: on", WaAcks.photos(true)); assertEquals("Photos and videos: off", WaAcks.photos(false))
        assertEquals("Not supported here", WaAcks.notSupported())
    }
}

class WaHelpTest {
    @Test fun helpIsParsedAndListsEveryCommandOnePerLine() {
        assertEquals(WaCommand.Help, WaCommands.parse(" Help "))
        assertEquals(WaCommand.Unknown, WaCommands.parse("help me"))
        assertEquals("Commands: add group <name>, remove group <name>, groups, photos on, photos off, help", WaAcks.help(true))
        assertEquals("Ready. Commands:\nadd group <name>\nremove group <name>\ngroups\nphotos on\nphotos off\nhelp", WaAcks.ready())
        assertTrue(WaAcks.usage(true).contains("photos on, photos off or help"))
    }

    @Test fun phoneGuardListsOnlyWhatItSupports() {
        assertEquals("Commands: add group <name>, remove group <name>, groups, help", WaAcks.help(false))
        assertFalse(WaAcks.usage(false).contains("photos"))
    }

    @Test fun theUpgradeNoteIsOnlyForAnUpgradedPhoneWithABoundAdminGroupThatIsConnected() {
        val v = WaAcks.COMMANDS_VERSION
        assertNotNull(WaAcks.upgradeNote(0, true, true, true, "1.0.0-rc3")) // from rc1 (nothing stored)
        assertNotNull(WaAcks.upgradeNote(2, true, true, true, "1.0.0-rc3")) // from a build that knew version 2
        assertTrue(WaAcks.upgradeNote(0, true, true, true, "1.0.0-rc3")!!.startsWith("Updated to 1.0.0-rc3. New: photos on, photos off, help."))
        assertNull(WaAcks.upgradeNote(v, true, true, true, "x")) // already told
        assertNull(WaAcks.upgradeNote(0, false, true, true, "x")) // setup not finished
        assertNull(WaAcks.upgradeNote(0, true, false, true, "x")) // no admin group
        assertNull(WaAcks.upgradeNote(0, true, true, false, "x")) // not connected yet
    }
}

class WaStickerLabelTest {
    private val now = 10_000_000L
    private fun sticker(ageMs: Long, mine: Boolean = true, kind: String = "sticker") = WaMsg("", now - ageMs, mine, "", "m", kind)

    @Test fun theLabelIsSkippedOnlyDirectlyAfterOwnStickerWithinTwoMinutes() {
        assertFalse(WaLabels.needsLabel(sticker(30_000), 0, now))
        assertFalse(WaLabels.needsLabel(sticker(119_000), 0, now))
        assertTrue(WaLabels.needsLabel(sticker(120_000), 0, now)) // two minutes or more
        assertTrue(WaLabels.needsLabel(null, 0, now)) // an empty chat
    }

    @Test fun anythingElseInBetweenBringsTheLabelBack() {
        assertTrue(WaLabels.needsLabel(sticker(10_000, mine = false), 0, now)) // someone else posted last
        assertTrue(WaLabels.needsLabel(WaMsg("hi", now - 5_000, true, "", "m", "text"), 0, now)) // our own text, not a sticker
        assertTrue(WaLabels.needsLabel(sticker(30_000), now - 5_000, now)) // a reaction arrived since
        assertFalse(WaLabels.needsLabel(sticker(30_000), now - 60_000, now)) // a reaction from before the sticker does not count
        assertTrue(WaLabels.needsLabel(WaMsg("", now - 5_000, true, "", "m", "sticker", deleted = true), 0, now))
    }

    @Test fun theStickerPlanHasTheLabelOnlyWhenNeeded() {
        assertEquals(2, WaLabels.sticker("Sam", "/s.webp", false, true).size)
        assertEquals(listOf<WaLabels.Step>(WaLabels.Step.Sticker("/s.webp", true)), WaLabels.sticker("Sam", "/s.webp", true, false))
    }
}

class WaHelpStatusTest {
    private class St(override val child: String = "Sam", override val groups: List<String> = listOf("Family", "Cousins"), override val photosAllowed: Boolean? = false,
        override val wifiOnlyVideos: Boolean? = true, override val showMedia: Boolean? = true, override val readReceipts: Boolean? = true, override val connected: Boolean = true,
        override val version: String = "1.0.0-rc7") : WaAcks.HelpStatus

    @Test fun helpShowsTheCommandsAndTheSettingsOneLineEach() {
        assertEquals("""Commands: add group <name>, remove group <name>, groups, photos on, photos off, help
Sam can use: Family, Cousins
Photos and videos: off
Videos download: on Wi-Fi only
Pictures, GIFs and stickers: shown
Tell WhatsApp when read: on
Connection: up
Version: 1.0.0-rc7""", WaAcks.help(true, St()))
    }

    @Test fun otherValuesReadInPlainWords() {
        val h = WaAcks.help(true, St(groups = emptyList(), photosAllowed = true, wifiOnlyVideos = false, showMedia = false, readReceipts = false, connected = false))
        assertTrue(h.contains("Sam can use no groups yet.")); assertTrue(h.contains("Photos and videos: on")); assertTrue(h.contains("Videos download: on any connection"))
        assertTrue(h.contains("Pictures, GIFs and stickers: hidden")); assertTrue(h.contains("Tell WhatsApp when read: off")); assertTrue(h.contains("Connection: down"))
    }

    @Test fun phoneGuardShowsOnlyWhatItHas() {
        val h = WaAcks.help(false, St(photosAllowed = null, wifiOnlyVideos = null, showMedia = null, readReceipts = null))
        assertEquals("Commands: add group <name>, remove group <name>, groups, help\nSam can use: Family, Cousins\nConnection: up\nVersion: 1.0.0-rc7", h)
    }

    @Test fun theGroupsLineIsTheSameInHelpAndInGroups() {
        assertTrue(WaAcks.help(true, St()).lines().contains(WaAcks.canUse("Sam", listOf("Family", "Cousins"))))
    }

    @Test fun theReplyStaysShort() { assertTrue(WaAcks.help(true, St(groups = (1..60).map { "Group number $it" })).length <= 900) }
}
