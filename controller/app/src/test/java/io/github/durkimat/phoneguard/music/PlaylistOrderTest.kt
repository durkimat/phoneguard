package io.github.durkimat.phoneguard.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistOrderTest {
    private val day = 24L * 3600 * 1000
    private val now = 100 * day
    private fun l(vararg n: String) = n.map { SpItem("spotify:playlist:$it", it, "") }
    private fun u(n: String) = "spotify:playlist:$n"
    private fun names(x: List<SpItem>) = x.map { it.label }

    @Test fun withNothingPinnedOrPlayedSpotifysOrderIsKept() { assertEquals(listOf("a", "b", "c"), names(PlaylistOrder.order(l("a", "b", "c"), emptySet(), emptyMap(), now))) }

    @Test fun pinnedFirstThenRecentThenTheRest() {
        val r = PlaylistOrder.order(l("a", "b", "c", "d", "e"), setOf(u("d")), mapOf(u("c") to now - day, u("b") to now - 2 * day), now)
        assertEquals(listOf("d", "c", "b", "a", "e"), names(r))
    }

    @Test fun pinnedAreOrderedByMostRecentPlayThenSpotifysOrder() {
        val r = PlaylistOrder.order(l("a", "b", "c", "d"), setOf(u("a"), u("b"), u("c"), u("d")), mapOf(u("c") to now - 5 * day, u("b") to now - day), now)
        assertEquals(listOf("b", "c", "a", "d"), names(r))
    }

    @Test fun aPlayOlderThanFourteenDaysDoesNotMoveAPlaylist() {
        val r = PlaylistOrder.order(l("a", "b", "c"), emptySet(), mapOf(u("c") to now - 15 * day, u("b") to now - 13 * day), now)
        assertEquals(listOf("b", "a", "c"), names(r))
    }

    @Test fun tiesInPlayTimeKeepSpotifysOrder() {
        val r = PlaylistOrder.order(l("a", "b", "c"), emptySet(), mapOf(u("c") to now - day, u("b") to now - day), now)
        assertEquals(listOf("b", "c", "a"), names(r))
    }

    @Test fun aStalePinOrPlayForAPlaylistNotInTheListIsIgnored() {
        val r = PlaylistOrder.order(l("a", "b"), setOf(u("gone")), mapOf(u("gone") to now), now)
        assertEquals(listOf("a", "b"), names(r))
        // it is still in the stored set, so a restored playlist comes back pinned
        assertEquals(listOf("gone", "a", "b"), names(PlaylistOrder.order(l("a", "b", "gone"), setOf(u("gone")), emptyMap(), now)))
    }

    @Test fun pinningTogglesAndTheListIsCapped() {
        val (p, on) = PlaylistOrder.toggle(emptySet(), "x")!!; assertTrue(on); assertEquals(setOf("x"), p)
        val (q, off) = PlaylistOrder.toggle(p, "x")!!; assertEquals(false, off); assertTrue(q.isEmpty())
        val full = (1..PlaylistOrder.MAX_PINS).map { "p$it" }.toSet()
        assertNull(PlaylistOrder.toggle(full, "extra"))
        assertEquals(PlaylistOrder.MAX_PINS - 1, PlaylistOrder.toggle(full, "p1")!!.first.size) // unpinning still works when full
    }

    @Test fun onlyTheNewestPlaysAreKept() {
        val many = (1..150).associate { "u$it" to it.toLong() }
        val k = PlaylistOrder.prune(many)
        assertEquals(PlaylistOrder.MAX_PLAYED, k.size); assertTrue("u150" in k && "u51" in k && "u50" !in k)
    }
}
