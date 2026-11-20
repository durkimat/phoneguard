package io.github.durkimat.wacore

import org.junit.Assert.assertEquals
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
