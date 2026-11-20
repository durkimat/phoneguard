package io.github.durkimat.familychat

import org.junit.Assert.*
import org.junit.Test

class SetupTest {
    private class Memory : Settings {
        override var childName = ""; override var parentName = ""; override var linked = false
        override var adminGroupId = ""; override var pin: PinStore.Saved? = null
    }

    @Test fun stepsGoInOrder() {
        val s = Memory(); val f = SetupFlow(s)
        assertEquals(SetupFlow.Step.NAMES, f.step)
        assertFalse(f.setNames("  ", "Mum")); assertFalse(f.setNames("Tom", "x".repeat(31)))
        assertTrue(f.setNames(" Tom ", "Mum")); assertEquals("Tom", s.childName)
        assertEquals(SetupFlow.Step.LINK, f.step)
        s.linked = true; assertEquals(SetupFlow.Step.ADMIN_GROUP, f.step)
        assertTrue(f.bindAdmin("123-456@g.us")); assertEquals("123-456", s.adminGroupId)
        assertEquals(SetupFlow.Step.PIN, f.step)
        assertFalse(f.setPin("12")); assertFalse(f.setPin("12ab")); assertTrue(f.setPin("4821"))
        assertEquals(SetupFlow.Step.DONE, f.step)
    }

    @Test fun adminGroupIsFoundByTheChildsNameOnly() {
        val s = Memory().apply { childName = "Tom" }
        val f = SetupFlow(s)
        val groups = "111|ChildApp-Tom\n222|ChildApp-Anna\n333|Football\n444|childapp-tom"
        assertEquals(listOf("111", "444"), f.adminCandidates(groups).map { it.first })
        assertEquals("ChildApp-Tom", f.adminGroupName())
    }

    @Test fun pinIsHashedAndChecked() {
        val saved = PinStore.create("4821")
        assertNotEquals("4821", saved.hash)
        assertTrue(PinStore.check("4821", saved)); assertFalse(PinStore.check("4822", saved)); assertFalse(PinStore.check("4821", null))
        assertNotEquals(saved.salt, PinStore.create("4821").salt)
    }

    @Test fun wrongTriesSlowDown() {
        assertEquals(0, PinStore.lockSeconds(4)); assertEquals(30, PinStore.lockSeconds(5)); assertEquals(900, PinStore.lockSeconds(20))
    }
}
