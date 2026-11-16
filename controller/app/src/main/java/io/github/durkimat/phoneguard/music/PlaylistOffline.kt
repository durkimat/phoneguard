package io.github.durkimat.phoneguard.music

/**
 * Pin and offline (downloaded) state of the Playlists screen, as one pure rule tested without a phone. A downloaded playlist is pinned automatically, so it sorts in
 * the pinned block; removing the download puts the pin back as it was before the download ([Offline.prePinned]). Spotify never tells us the download state later,
 * so this record is the phone's own memory (corrected whenever a job opens the playlist and reads the button).
 */
object PlaylistOffline {
    const val DOWNLOADED = "↓ "
    const val DOWNLOADING = "… "
    const val PINNED = "* "

    data class Offline(val name: String, val prePinned: Boolean, val at: Long)

    /** What the pins and records are after a download finished for [uri]: pinned, remembering whether it was pinned before. */
    fun downloaded(pins: Set<String>, rec: Map<String, Offline>, uri: String, name: String, now: Long): Pair<Set<String>, Map<String, Offline>> {
        val was = rec[uri]?.prePinned ?: (uri in pins)
        return (pins + uri) to (rec + (uri to Offline(name, was, now)))
    }

    /** After the download was removed: the record goes, and the playlist is pinned only if it was before the download. */
    fun removed(pins: Set<String>, rec: Map<String, Offline>, uri: String): Pair<Set<String>, Map<String, Offline>> {
        val r = rec[uri] ?: return pins to rec
        return (if (r.prePinned) pins else pins - uri) to (rec - uri)
    }

    /** The one marker in front of a playlist's name. */
    fun marker(uri: String, pins: Set<String>, rec: Map<String, Offline>, busy: Set<String>): String = when {
        uri in busy -> DOWNLOADING
        uri in rec -> DOWNLOADED
        uri in pins -> PINNED
        else -> ""
    }

    /** What to show when Spotify's list cannot be loaded: the downloaded playlists from the record, in the screen's order. */
    fun offlineList(rec: Map<String, Offline>): List<SpItem> = rec.entries.sortedBy { it.value.name.lowercase() }.map { SpItem(it.key, it.value.name, "") }

    /** The playlist's Spotify id for `spotify:playlist:<id>` addresses (null for anything else). */
    fun idOf(uri: String): String? = uri.removePrefix("spotify:playlist:").takeIf { uri.startsWith("spotify:playlist:") && it.isNotEmpty() && ':' !in it }
}
