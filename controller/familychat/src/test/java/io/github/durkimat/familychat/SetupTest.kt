package io.github.durkimat.familychat

import org.junit.Assert.*
import org.junit.Test

internal class Memory : Settings {
    override var riskAccepted = false; override var childName = ""; override var parentName = ""; override var linked = false
    override var adminGroupId = ""; override var pin: PinStore.Saved? = null; override var pinFailures = 0; override var pinLockUntil = 0L
    override var allowedGroups: List<Group> = emptyList(); override var lastCommand = 0L; override var setupDone = false; override var sendReadReceipts = true
    override var knownGroups: List<Group> = emptyList()
    override fun clear() { riskAccepted = false; childName = ""; parentName = ""; linked = false; adminGroupId = ""; pin = null; allowedGroups = emptyList(); setupDone = false }
}

class SetupTest {
    @Test fun stepsGoInOrder() {
        val s = Memory(); val f = SetupFlow(s)
        assertEquals(SetupFlow.Step.WELCOME, f.step); assertEquals(1, f.number)
        f.acceptRisk(); assertEquals(SetupFlow.Step.PIN, f.step)
        assertFalse(f.setPin("12", "12")); assertFalse(f.setPin("12ab", "12ab")); assertFalse(f.setPin("4821", "4822")); assertTrue(f.setPin("4821", "4821"))
        assertEquals(SetupFlow.Step.NAMES, f.step); assertEquals(3, f.number)
        assertFalse(f.setNames("  ", "Mum")); assertFalse(f.setNames("Tom", "x".repeat(31)))
        assertTrue(f.setNames(" Tom ", "Mum")); assertEquals("Tom", s.childName)
        assertEquals(SetupFlow.Step.LINK, f.step)
        s.linked = true; assertEquals(SetupFlow.Step.ADMIN_GROUP, f.step)
        assertTrue(f.bindAdmin("123-456@g.us")); assertEquals("123-456", s.adminGroupId)
        assertEquals(SetupFlow.Step.GROUPS, f.step); assertEquals(6, f.number)
        f.finish(listOf(Group("1", "Family"), Group("123-456", "FamilyChat-Tom")))
        assertEquals(SetupFlow.Step.DONE, f.step)
        assertEquals(listOf("1"), s.allowedGroups.map { it.id }) // the admin group is never allowed
    }

    @Test fun adminGroupIsFoundByTheChildsNameOnly() {
        val s = Memory().apply { childName = "Tom" }
        val f = SetupFlow(s)
        val groups = "111|FamilyChat-Tom\n222|FamilyChat-Anna\n333|Football\n444|familychat-tom"
        assertEquals(listOf("111", "444"), f.adminCandidates(groups).map { it.first })
        assertEquals("FamilyChat-Tom", f.adminGroupName())
    }

    @Test fun pinIsHashedAndChecked() {
        val saved = PinStore.create("4821")
        assertNotEquals("4821", saved.hash); assertEquals(4, saved.length)
        assertTrue(PinStore.check("4821", saved)); assertFalse(PinStore.check("4822", saved)); assertFalse(PinStore.check("4821", null))
        assertNotEquals(saved.salt, PinStore.create("4821").salt)
        assertEquals(6, PinStore.create("123456").length)
    }

    @Test fun wrongTriesSlowDown() {
        assertEquals(0, PinStore.lockSeconds(4)); assertEquals(30, PinStore.lockSeconds(5)); assertEquals(900, PinStore.lockSeconds(20))
    }

    @Test fun theGateLocksAndForgivesAfterTheWait() {
        val s = Memory().apply { pin = PinStore.create("4821") }
        var t = 1_000_000L
        val g = PinGate(s) { t }
        repeat(4) { assertTrue(g.attempt("0000") is PinGate.Result.Wrong) }
        val fifth = g.attempt("0000") as PinGate.Result.Wrong
        assertEquals(30, fifth.lockedSeconds)
        assertEquals(PinGate.Result.Locked(30), g.attempt("4821")) // even the right PIN waits
        t += 31_000
        assertEquals(PinGate.Result.Ok, g.attempt("4821"))
        assertEquals(0, s.pinFailures)
    }
}

class PickerTest {
    private val all = listOf(Group("1", "Zoo"), Group("2", "apple"), Group("3", "Football"), Group("4", "Football"), Group("5", "Family"))

    @Test fun listIsAToZAndNothingIsTickedByDefault() {
        val m = PickerModel(all)
        assertEquals(listOf("apple", "Family", "Football", "Football", "Zoo"), m.rows().map { it.group.name })
        assertTrue(m.rows().none { it.checked }); assertEquals(0, m.chosenGroups().size); assertEquals(5, m.total)
    }

    @Test fun sameNamesGetTheEndOfTheIdAndOthersDoNot() {
        val rows = PickerModel(all).rows()
        assertEquals(listOf(null, null, "ends 3", "ends 4", null), rows.map { it.hint })
    }

    @Test fun searchFiltersAsYouTypeAndSaysWhenNothingMatches() {
        val m = PickerModel(all); m.query = "FOO"
        assertEquals(listOf("3", "4"), m.rows().map { it.group.id })
        m.query = "xyz"; assertTrue(m.rows().isEmpty()); assertEquals(5, m.total)
    }

    @Test fun chosenAreKeptByIdAndRemovedByChip() {
        val m = PickerModel(all)
        m.toggle(all[2]); m.toggle(all[4])
        assertEquals(listOf("Family", "Football"), m.chosenGroups().map { it.name }); assertTrue(m.isChosen("3")); assertFalse(m.isChosen("4"))
        m.query = "zoo"; assertEquals(1, m.rows().size); assertEquals(2, m.chosenGroups().size) // the search does not hide what is chosen
        m.remove("3"); assertEquals(listOf("Family"), m.chosenGroups().map { it.name })
        m.toggle(all[4]); assertTrue(m.chosenGroups().isEmpty())
    }

    @Test fun aGroupTheAccountHasLeftCanStillBeRemoved() {
        val m = PickerModel(all, listOf(Group("9", "Old")))
        assertEquals(listOf("Old"), m.chosenGroups().map { it.name }); assertTrue(m.rows().none { it.group.id == "9" })
        m.remove("9"); assertTrue(m.chosenGroups().isEmpty())
    }

    @Test fun groupsParseFromTheTransportLines() {
        assertEquals(listOf(Group("1-2", "A b"), Group("3", "C")), Group.parse("1-2@g.us|A b\n\n|x\n3|C\n3|dup\n4|"))
    }
}

class AdminBrainTest {
    private var allowed = listOf<Group>()
    private val lines = "1|Family\n2|Grandparents\n3|Football\n4|Football\n9|FamilyChat-Sam"
    private fun brain() = AdminBrain({ "Sam" }, { "9" }, { allowed }, { allowed = it }, { lines })

    @Test fun addsRemovesAndLists() {
        val b = brain()
        assertEquals("Added: Family", b.handle("add group family"))
        assertEquals("Already allowed: Family", b.handle("add group Family"))
        b.handle("add group Grandparents")
        assertEquals("Sam can use: Family, Grandparents", b.handle("groups"))
        assertEquals("Removed: Family", b.handle("remove group Family"))
        assertEquals(listOf("2"), allowed.map { it.id })
        assertEquals("No group called Nope", b.handle("add group Nope"))
        assertEquals("No group called Family", b.handle("remove group Family"))
    }

    @Test fun theAdminGroupCannotBeAdded() {
        assertEquals("No group called FamilyChat-Sam", brain().handle("add group FamilyChat-Sam")); assertTrue(allowed.isEmpty())
    }

    @Test fun twoGroupsWithOneNameAskForANumber() {
        val b = brain()
        assertEquals("Two groups are called Football:\n1) Football (ends 3)\n2) Football (ends 4)\nReply with a number.", b.handle("add group Football"))
        assertEquals("Added: Football", b.handle("2")); assertEquals(listOf("4"), allowed.map { it.id })
        assertEquals("Nothing to choose.", b.handle("1"))
    }

    @Test fun aNumberOutOfRangeOrOutOfTheBlueChoosesNothing() {
        val b = brain()
        assertEquals("Nothing to choose.", b.handle("1"))
        b.handle("add group Football"); assertEquals("Nothing to choose.", b.handle("7")); assertTrue(allowed.isEmpty())
    }

    @Test fun unknownTextGetsTheUsageLine() {
        assertTrue(brain().handle("hello").startsWith("I did not understand"))
        assertEquals("Sam can use no groups yet.", brain().handle("groups"))
    }
}
