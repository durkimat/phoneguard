package io.github.durkimat.phoneguard.music

import io.github.durkimat.phoneguard.music.PlaylistOffline.Offline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistOfflineTest {
    private fun u(n: String) = "spotify:playlist:$n"

    @Test fun downloadPinsAndRemovalRestoresAnUnpinnedPlaylist() {
        val (p, r) = PlaylistOffline.downloaded(emptySet(), emptyMap(), u("a"), "A", 5)
        assertTrue(u("a") in p); assertEquals(Offline("A", false, 5), r[u("a")])
        val (p2, r2) = PlaylistOffline.removed(p, r, u("a"))
        assertTrue(p2.isEmpty() && r2.isEmpty())
    }

    @Test fun aPlaylistPinnedBeforeTheDownloadStaysPinnedAfterRemoval() {
        val (p, r) = PlaylistOffline.downloaded(setOf(u("a")), emptyMap(), u("a"), "A", 5)
        assertEquals(true, r[u("a")]?.prePinned)
        assertEquals(setOf(u("a")), PlaylistOffline.removed(p, r, u("a")).first)
    }

    @Test fun downloadingAgainKeepsTheOriginalPrePinned() {
        val (p, r) = PlaylistOffline.downloaded(emptySet(), emptyMap(), u("a"), "A", 5)
        val (_, r2) = PlaylistOffline.downloaded(p, r, u("a"), "A", 9)
        assertEquals(false, r2[u("a")]?.prePinned)
    }

    @Test fun markersAreOneGlyphWithDownloadingFirst() {
        val rec = mapOf(u("a") to Offline("A", false, 1))
        val pins = setOf(u("a"), u("b"))
        assertEquals("↓ ", PlaylistOffline.marker(u("a"), pins, rec, emptySet()))
        assertEquals("… ", PlaylistOffline.marker(u("a"), pins, rec, setOf(u("a"))))
        assertEquals("* ", PlaylistOffline.marker(u("b"), pins, rec, emptySet()))
        assertEquals("", PlaylistOffline.marker(u("c"), pins, rec, emptySet()))
    }

    @Test fun downloadedPlaylistsSortInThePinnedBlock() {
        val items = listOf("a", "b", "c").map { SpItem(u(it), it, "") }
        val (p, _) = PlaylistOffline.downloaded(emptySet(), emptyMap(), u("c"), "c", 5)
        assertEquals(listOf("c", "a", "b"), PlaylistOrder.order(items, p, emptyMap(), 0).map { it.label })
    }

    @Test fun offlineListAndIds() {
        val rec = mapOf(u("z") to Offline("Zed", false, 1), u("a") to Offline("alpha", true, 1))
        assertEquals(listOf("alpha", "Zed"), PlaylistOffline.offlineList(rec).map { it.label })
        assertEquals("abc", PlaylistOffline.idOf(u("abc"))); assertNull(PlaylistOffline.idOf("spotify:track:abc"))
    }
}
