package io.github.durkimat.phoneguard.core

import io.github.durkimat.phoneguard.core.PlaylistDrive.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistDriveTest {
    private val sp = PlaylistDrive.SPOTIFY
    private val idle = listOf("Play", "Download Playlist: Road trip")
    private val busy = listOf("Play", "Downloading Playlist: Road trip")
    private val done = listOf("Play", "Downloaded Playlist: Road trip", "Downloaded")

    @Test fun downloadTapsTheButtonThenWaitsThenFinishes() {
        val d = PlaylistDrive(false)
        assertEquals(Step.Tap("Download Playlist:"), d.step(sp, idle))
        assertEquals(Step.Wait, d.step(sp, busy))
        assertEquals(Step.Wait, d.step(sp, busy))
        assertEquals(Step.Done("downloaded"), d.step(sp, done))
        assertEquals(PlaylistDrive.State.NOT_OFFLINE, d.firstSeen)
    }

    @Test fun aPlaylistAlreadyOfflineIsDoneAtOnceAndTheRecordCanBeCorrected() {
        val d = PlaylistDrive(false)
        assertEquals(Step.Done("already downloaded"), d.step(sp, done))
        assertEquals(PlaylistDrive.State.OFFLINE, d.firstSeen)
    }

    @Test fun noFreeSlotDialogIsAcknowledgedThenFails() {
        val d = PlaylistDrive(false)
        d.step(sp, idle)
        assertEquals(Step.Tap("OK"), d.step(sp, listOf("You're out of offline devices", "OK")))
        assertEquals(Step.Fail(PlaylistDrive.NO_SLOT), d.step(sp, idle))
    }

    @Test fun leavingSpotifyAfterTheTapAborts() {
        val d = PlaylistDrive(false)
        d.step(sp, idle)
        d.step("com.android.launcher", listOf("Home")); d.step("com.android.launcher", listOf("Home"))
        assertTrue(d.step("com.android.launcher", listOf("Home")) is Step.Fail)
    }

    @Test fun aPageThatNeverShowsTheButtonIsReopenedThenFails() {
        val d = PlaylistDrive(false)
        val seen = (1..20).map { d.step(sp, listOf("Search")) }
        assertEquals(Step.Open(1), seen[5])
        assertTrue(seen.any { it is Step.Fail })
    }

    @Test fun theDownloadGivesUpAfterTenMinutes() {
        val d = PlaylistDrive(false)
        d.step(sp, busy)
        val last = (1..PlaylistDrive.MAX_DOWNLOAD_STEPS).map { d.step(sp, busy) }.last()
        assertTrue(last is Step.Fail)
    }

    @Test fun removeTapsTheDownloadedButtonConfirmsAndFinishes() {
        val d = PlaylistDrive(true)
        assertEquals(Step.Tap("Downloaded Playlist:"), d.step(sp, done))
        assertEquals(Step.Tap("remove"), d.step(sp, done + listOf("Remove", "Cancel")))
        assertEquals(Step.Done("download removed"), d.step(sp, idle))
    }

    @Test fun removeDoesNotRetapWhileTheDialogIsClosing() {
        val d = PlaylistDrive(true)
        d.step(sp, done)
        d.step(sp, done + "Remove")
        assertEquals(Step.Wait, d.step(sp, done))
    }

    @Test fun removeOfAPlaylistThatIsNotOfflineIsDone() {
        assertEquals(Step.Done("already not downloaded"), PlaylistDrive(true).step(sp, idle))
    }
}
