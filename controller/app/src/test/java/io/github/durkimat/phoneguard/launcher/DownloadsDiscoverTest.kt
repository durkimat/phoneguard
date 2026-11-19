package io.github.durkimat.phoneguard.launcher

import io.github.durkimat.phoneguard.music.PlaylistOffline
import io.github.durkimat.phoneguard.music.SpItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spotify downloads are findable: the hint under the list, the per-playlist rows in the Spotify menu and the marks help. */
class DownloadsDiscoverTest {
    private fun playlists(): LauncherModel {
        val m = LauncherModel(listOf(MenuItem(MUSIC_PKG, "Music"))); m.spSignedIn = true
        m.openMusicFromHome(); m.onKey(Key.DIGIT, 2)
        m.setPlaylists(listOf("a", "b").map { SpItem("spotify:playlist:$it", it, "") })
        return m
    }

    @Test fun theHintFollowsTheHighlightedPlaylist() {
        val m = playlists(); assertEquals(Screen.PLAYLISTS, m.screen)
        assertEquals("# download   * pin", m.playlistHint())
        m.spOffline = mapOf("spotify:playlist:b" to PlaylistOffline.Offline("b", false, 1L)); m.onKey(Key.DOWN)
        assertEquals("# remove download", m.playlistHint())
        m.spBusy = setOf("spotify:playlist:b"); assertEquals("Downloading...", m.playlistHint())
    }

    @Test fun theSpotifyMenuOpenedFromPlaylistsEndsWithADownloadOrRemoveRowForThatPlaylist() {
        val m = playlists()
        m.onKey(Key.RIGHT); assertEquals(Screen.SPOTIFYMENU, m.screen)
        assertEquals("Download playlist", m.rows.last())
        val a = m.onKey(Key.DIGIT, m.rows.size); assertEquals(Action.SpOffline("spotify:playlist:a", "a", false), a)
        m.spOffline = mapOf("spotify:playlist:a" to PlaylistOffline.Offline("a", false, 1L)); assertEquals("Remove download", m.rows.last())
        assertEquals(Action.SpOffline("spotify:playlist:a", "a", true), m.onKey(Key.DIGIT, m.rows.size))
    }

    @Test fun theMenuOpenedFromNowPlayingHasNoPlaylistRowAndTheMarksHelpExplainsTheMarks() {
        val m = playlists(); m.onKey(Key.SOFT_RIGHT); m.onKey(Key.SOFT_RIGHT) // list menu, Now playing
        m.onKey(Key.SOFT_LEFT); assertEquals(Screen.SPOTIFYMENU, m.screen)
        assertEquals(false, m.rows.contains("Download playlist")); assertEquals(true, m.rows.contains("What do the marks mean?"))
        m.onKey(Key.DIGIT, m.rows.indexOf("What do the marks mean?") + 1)
        assertEquals(Screen.SPMARKS, m.screen); assertEquals("↓  downloaded for offline", m.rows[0])
        m.onKey(Key.SOFT_RIGHT); assertEquals(Screen.SPOTIFYMENU, m.screen)
    }
}
