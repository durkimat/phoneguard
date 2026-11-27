package io.github.durkimat.phoneguard.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPlaylistsTest {
    private fun page(n: Int, next: String?) = """{"items":[${(1..n).joinToString(",") { """{"uri":"spotify:playlist:$it","name":"List $it","tracks":{"total":${it % 3 * 10}}}""" }}],"next":${if (next == null) "null" else "\"$next\""}}"""

    @Test fun aPageKeepsItsPlaylistsAndTheTrackCountLabel() {
        val (items, more) = Spotify.parsePlaylistPage(page(3, "https://api.spotify.com/v1/me/playlists?offset=50&limit=50"))
        assertEquals(3, items.size); assertTrue(more)
        assertEquals("", items[2].sub)                 // 3 % 3 = 0 tracks: no label
        assertEquals("10 tracks", items[0].sub); assertEquals("List 2", items[1].label)
    }

    @Test fun theTrackCountIsReadUnderSpotifysNewNameAndTheOld() {
        val text = """{"items":[{"uri":"a","name":"New","items":{"total":12}},{"uri":"b","name":"Old","tracks":{"total":7}},{"uri":"c","name":"Not theirs"}],"next":null}"""
        assertEquals(listOf("12 tracks", "7 tracks", ""), Spotify.parsePlaylistPage(text).first.map { it.sub })
    }

    @Test fun theLastPageHasNoNext() { assertFalse(Spotify.parsePlaylistPage(page(2, null)).second) }

    @Test fun anEmptyPageIsEmpty() { val (items, more) = Spotify.parsePlaylistPage("""{"items":[],"next":null}"""); assertTrue(items.isEmpty()); assertFalse(more) }

    @Test fun theCapIsAWholeNumberOfPages() { assertEquals(0, Spotify.PLAYLIST_CAP % Spotify.PLAYLIST_PAGE); assertEquals(300, Spotify.PLAYLIST_CAP) }
}
