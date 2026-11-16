package io.github.durkimat.phoneguard.launcher

import io.github.durkimat.phoneguard.music.SpItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistPinTest {
    private fun model(): LauncherModel {
        val m = LauncherModel(listOf(MenuItem(MUSIC_PKG, "Music")))
        m.spSignedIn = true
        m.onKey(Key.SOFT_RIGHT); m.onKey(Key.DIGIT, 2)              // Music menu, Spotify: the Playlists screen
        assertEquals(Screen.PLAYLISTS, m.screen)
        m.setPlaylists(listOf("a", "b", "c").map { n -> SpItem("spotify:playlist:$n", n, "") })
        return m
    }

    @Test fun starPinsTheHighlightedPlaylistMovesItFirstAndKeepsTheCursorOnIt() {
        val m = model()
        m.onKey(Key.DOWN); m.onKey(Key.DOWN)                        // "c"
        assertEquals(2, m.selected)
        assertEquals(Action.SpPin("spotify:playlist:c", true), m.onKey(Key.STAR))
        assertEquals(listOf("* c", "a", "b"), m.spPlaylists.map { it.label })
        assertEquals(0, m.selected)                                 // still on c
        assertEquals("Pinned", m.notice)
        assertEquals(Action.SpPin("spotify:playlist:c", false), m.onKey(Key.STAR)) // unpin
        assertEquals(listOf("a", "b", "c"), m.spPlaylists.map { it.label }); assertEquals(2, m.selected); assertEquals("Unpinned", m.notice)
    }

    @Test fun starDoesNothingOnTheDevicesList() {
        val m = LauncherModel(listOf(MenuItem(MUSIC_PKG, "Music"))); m.spSignedIn = true
        m.onKey(Key.SOFT_RIGHT); m.onKey(Key.DIGIT, 2); m.onKey(Key.SOFT_RIGHT)            // the Spotify menu
        m.onKey(Key.DIGIT, 3)
        if (m.screen == Screen.DEVICES) assertEquals(Action.None, m.onKey(Key.STAR))
    }
}
