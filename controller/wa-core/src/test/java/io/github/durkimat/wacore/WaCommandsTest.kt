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
