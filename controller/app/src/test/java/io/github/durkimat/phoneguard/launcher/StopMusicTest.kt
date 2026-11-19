package io.github.durkimat.phoneguard.launcher

import io.github.durkimat.phoneguard.music.MusicWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopMusicTest {
    private fun musicModel(): LauncherModel = LauncherModel(listOf(MenuItem(MUSIC_PKG, "Music"))).also { it.radioNames = listOf("BBC Radio 1"); it.spSignedIn = true }

    @Test fun stopMusicIsTheLastRowOfAProviderMenuAndOnlyWhileItHasSomething() {
        val m = musicModel(); m.openMusicFromHome(); m.onKey(Key.DIGIT, 1) // Radio presets
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.RADIOMENU, m.screen)
        assertEquals(listOf("Presets", "Find station", "Browse stations", "Change source"), m.rows) // nothing playing or paused: no Stop row
        m.provStatus = mapOf(Provider.RADIO to 1); assertEquals("Stop music", m.rows.last()); assertEquals(listOf("Presets", "Find station", "Browse stations", "Change source", "Stop music"), m.rows)
        val a = m.onKey(Key.DIGIT, 5); assertEquals(Action.MusicStop(Provider.RADIO), a); assertEquals(Screen.HOME, m.screen); assertEquals("Stopped", m.notice)
    }

    @Test fun spotifyStopRowFollowsSignOut() {
        val m = musicModel(); m.provStatus = mapOf(Provider.SPOTIFY to 2); m.openMusicFromHome()
        m.onKey(Key.DIGIT, 2); m.onKey(Key.SOFT_RIGHT) // Spotify Now playing screen's menu is reached with the right key from the list; use the soft left on Now playing
        m.goHome(); m.openMusicFromHome(); m.onKey(Key.DIGIT, 2); assertEquals(Screen.MUSIC, m.screen)
        m.onKey(Key.SOFT_LEFT); assertEquals(Screen.SPOTIFYMENU, m.screen)
        assertEquals(listOf("Playlists", "Search", "Play on...", "Change source", "What the marks mean", "Sign out of Spotify", "Stop music"), m.rows)
        assertEquals(Action.MusicStop(Provider.SPOTIFY), m.onKey(Key.DIGIT, 7))
    }

    @Test fun pausedLongerThanTwoHoursExpires() {
        assertFalse(MusicWatch.expired(0L, 10 * 3_600_000L)) // not paused
        assertFalse(MusicWatch.expired(1_000L, 1_000L + 2 * 3_600_000L - 1))
        assertTrue(MusicWatch.expired(1_000L, 1_000L + 2 * 3_600_000L))
    }
}
