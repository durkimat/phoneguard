package io.github.durkimat.phoneguard.core

import io.github.durkimat.phoneguard.core.StoreDrive.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreDriveTest {
    private val aurora = "com.aurora.store"
    private val installer = "com.google.android.packageinstaller"

    @Test fun theFirstRunPagesAreWalkedThenTheStorePageIsOpenedOnce() {
        val d = StoreDrive()
        assertEquals(Step.Tap("Next"), d.step(aurora, listOf("Welcome", "Next")))
        assertEquals(Step.Tap("Allow"), d.step(aurora, listOf("Allow", "Finish").take(1)))
        assertEquals(Step.Tap("Finish"), d.step(aurora, listOf("Finish")))
        assertEquals(Step.Tap("Anonymous"), d.step(aurora, listOf("Google", "Anonymous")))
        assertEquals(Step.Open, d.step(aurora, listOf("Top charts", "For you")))
        assertEquals(Step.Wait, d.step(aurora, listOf("Top charts")))
    }

    @Test fun anInstallButtonPressesAreCappedAndTheInstallerIsFollowed() {
        val d = StoreDrive()
        repeat(4) { assertEquals(Step.Tap("Install"), d.step(aurora, listOf("Spotify", "Install"))) }
        assertTrue(d.pressedInstall)
        assertEquals(Step.Wait, d.step(aurora, listOf("Spotify", "Install")))               // not a fifth press
        assertEquals(Step.Tap("Install"), d.step(installer, listOf("Do you want to install this app?", "Cancel", "Install")))
        assertEquals(Step.Wait, d.step(installer, listOf("Installing")))
    }

    @Test fun theContinueToAuroraPromptIsConfirmed() {
        assertEquals(Step.Tap("Continue"), StoreDrive().step("android", listOf("Continue to Aurora", "Continue", "Cancel")))
    }

    @Test fun aMissingAppIsReportedInPlainWords() {
        val s = StoreDrive().step(aurora, listOf("Item not found"))
        assertEquals(Step.Fail("the app was not found in the store"), s)
    }

    @Test fun theInstallPermissionScreenAsksTheHelperThenGivesUp() {
        val d = StoreDrive()
        repeat(6) { assertEquals(Step.AskInstallPermission, d.step(aurora, listOf("Installer permission", "Grant"))) }
        assertTrue(d.step(aurora, listOf("Installer permission", "Grant")) is Step.Fail)
    }

    @Test fun noConnectionRetriesTwiceThenFails() {
        val d = StoreDrive()
        assertEquals(Step.Tap("Retry"), d.step(aurora, listOf("No internet", "Retry")))
        assertEquals(Step.Tap("Retry"), d.step(aurora, listOf("No internet", "Retry")))
        assertEquals(Step.Fail("the app store could not reach Google Play"), d.step(aurora, listOf("No internet", "Retry")))
    }

    @Test fun aStuckHomeScreenOpensThePageAgainAFewTimesOnly() {
        val d = StoreDrive()
        assertEquals(Step.Open, d.step(aurora, listOf("Top charts")))
        var opens = 1
        repeat(30) { if (d.step(aurora, listOf("Top charts")) == Step.Open) opens++ }
        assertEquals(3, opens)
    }

    @Test fun theJobTimesOutWithAReasonThatSaysHowFarItGot() {
        val a = StoreDrive(); var last: Step = Step.Wait
        repeat(StoreDrive.MAX_STEPS + 1) { last = a.step(aurora, listOf("Loading")) }
        assertEquals(Step.Fail("the app store did not get as far as the Install button"), last)
        val b = StoreDrive(); b.step(installer, listOf("Install"))
        repeat(StoreDrive.MAX_STEPS) { last = b.step(installer, listOf("Installing")) }
        assertEquals(Step.Fail("Android did not let the install go ahead"), last)
    }
}
