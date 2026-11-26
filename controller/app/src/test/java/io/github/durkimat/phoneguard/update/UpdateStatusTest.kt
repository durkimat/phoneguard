package io.github.durkimat.phoneguard.update

import io.github.durkimat.phoneguard.update.SelfInstall.Outcome
import org.junit.Assert.*
import org.junit.Test

class UpdateStatusTest {
    private fun st(phase: Phase, detail: String = "", percent: Int = -1, version: String = "0.5.1") = UpdateStatus("j1", version, phase, detail, percent, 5)

    @Test fun theWordsSayWhyItWaitsAndHowFarItIs() {
        assertEquals("Waiting to get 0.5.1: needs Wi-Fi", st(Phase.WAITING, "no Wi-Fi").words)
        assertEquals("Waiting to get 0.5.1: battery 20%, needs 25% or charging", st(Phase.WAITING, "battery 20%").words)
        assertEquals("Waiting to get 0.5.1: not enough free space", st(Phase.WAITING, "storage").words)
        assertEquals("Downloading 0.5.1: 40%", st(Phase.DOWNLOADING, percent = 40).words)
        assertEquals("Could not update to 0.5.1: checksum does not match", st(Phase.FAILED, "checksum does not match").words)
        assertEquals("Downloading the update", st(Phase.DOWNLOADING, version = "").words)
    }

    @Test fun theParentIsToldOfANewStageOrReasonButNotOfEveryPercentOrBatteryPoint() {
        assertEquals(st(Phase.DOWNLOADING, percent = 5).reportKey, st(Phase.DOWNLOADING, percent = 95).reportKey)
        assertEquals(st(Phase.WAITING, "battery 20%").reportKey, st(Phase.WAITING, "battery 19%").reportKey)
        assertNotEquals(st(Phase.WAITING, "no Wi-Fi").reportKey, st(Phase.WAITING, "storage").reportKey)
        assertNotEquals(st(Phase.WAITING).reportKey, st(Phase.DOWNLOADING).reportKey)
        assertNotEquals(st(Phase.WAITING, "no Wi-Fi").reportKey, st(Phase.WAITING, "no Wi-Fi").copy(job = "j2").reportKey)
    }

    @Test fun theStatusSurvivesBeingSaved() {
        val s = st(Phase.FAILED, "download HTTP 404", 30); assertEquals(s, UpdateStatus.fromJson(s.toJson()))
        assertNull(UpdateStatus.fromJson(org.json.JSONObject().put("job", "x").put("phase", "nonsense")))
    }

    @Test fun theVersionIsReadFromTheJobsName() {
        assertEquals("0.5.1", UpdateStatus.versionIn("Phone Guard 0.5.1")); assertEquals("", UpdateStatus.versionIn("Phone Guard test build"))
    }

    @Test fun networkErrorsAreSaidPlainly() {
        assertEquals("could not reach the internet or the server", UpdateStatus.plainError("UnknownHostException: Unable to resolve host \"x\""))
        assertEquals("Could not update to 0.5.1: the connection timed out", st(Phase.FAILED, "download: SocketTimeoutException").words)
        assertEquals("Android refused it: it is signed with a different key", UpdateStatus.plainError("Android refused it: INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package x"))
        assertEquals("HTTP 403 replay", UpdateStatus.plainError("HTTP 403 replay")); assertEquals(90, UpdateStatus.plainError("x".repeat(200)).length)
    }

    private val going = SelfInstall("j1", "0.5.1", codeBefore = 500, updatedBefore = 1000, startedAt = 10_000_000)

    @Test fun onlyAChangeInTheInstalledAppProvesTheUpdate() {
        assertEquals(Outcome.STILL_GOING, going.outcome(500, 1000, going.startedAt + 60_000))
        assertEquals(Outcome.INSTALLED, going.outcome(501, 2000, going.startedAt + 60_000))
        assertEquals(Outcome.INSTALLED, going.outcome(500, 2000, going.startedAt + 60_000)) // the same version put on again
    }

    @Test fun afterTenMinutesWithNoChangeItDidNotGoIn() {
        assertEquals(Outcome.STILL_GOING, going.outcome(500, 1000, going.startedAt + SelfInstall.GIVE_UP_MS - 1))
        assertEquals(Outcome.NOT_INSTALLED, going.outcome(500, 1000, going.startedAt + SelfInstall.GIVE_UP_MS))
        assertEquals(Outcome.NOT_INSTALLED, going.outcome(500, 1000, going.startedAt - 3_600_000)) // the clock was put back
        assertEquals(going, SelfInstall.fromJson(going.toJson()))
    }
}
