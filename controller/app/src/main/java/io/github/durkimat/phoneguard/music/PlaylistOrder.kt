package io.github.durkimat.phoneguard.music

/**
 * The order of the Playlists screen: pinned first (the one played most recently first, else Spotify's order), then playlists played in the last 14 days
 * (most recent first), then the rest in Spotify's order. A pure rule, tested without a phone. A pin or a play that is not in the list is simply ignored
 * (a restored playlist comes back pinned).
 */
object PlaylistOrder {
    const val RECENT_DAYS = 14
    const val MAX_PINS = 50
    const val MAX_PLAYED = 100

    fun order(items: List<SpItem>, pins: Set<String>, played: Map<String, Long>, now: Long): List<SpItem> {
        val index = items.withIndex().associate { it.value.uri to it.index }
        fun at(i: SpItem) = played[i.uri] ?: -1L
        val byRecentThenSpotify = compareByDescending<SpItem> { at(it) }.thenBy { index[it.uri] ?: Int.MAX_VALUE }
        val pinned = items.filter { it.uri in pins }.sortedWith(byRecentThenSpotify)
        val since = now - RECENT_DAYS * 24L * 3600 * 1000
        val recent = items.filter { it.uri !in pins && at(it) >= since }.sortedWith(byRecentThenSpotify)
        val shown = (pinned + recent).map { it.uri }.toSet()
        return pinned + recent + items.filter { it.uri !in shown }
    }

    /** Pin or unpin [uri]: the new set and whether it is now pinned; null when it would need a 51st pin. */
    fun toggle(pins: Set<String>, uri: String): Pair<Set<String>, Boolean>? =
        if (uri in pins) (pins - uri) to false else if (pins.size >= MAX_PINS) null else (pins + uri) to true

    /** The plays to keep: the newest [MAX_PLAYED]. */
    fun prune(played: Map<String, Long>): Map<String, Long> = if (played.size <= MAX_PLAYED) played else played.entries.sortedByDescending { it.value }.take(MAX_PLAYED).associate { it.key to it.value }
}
