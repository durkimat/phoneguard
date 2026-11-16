package io.github.durkimat.phoneguard.core

/**
 * What to press next while the phone downloads (or removes the download of) a Spotify playlist by itself. A pure decision, tested without a phone: it is given
 * what is on the screen (the front window's package and the words and descriptions of its controls) and says what to do. Spotify's playlist page has one button
 * whose description is "Download Playlist: <name>" (not offline), "Downloading Playlist: <name>" or "Downloaded Playlist: <name>".
 */
class PlaylistDrive(private val removing: Boolean) {
    sealed class Step {
        data class Tap(val label: String) : Step()
        object Wait : Step()
        /** Open the playlist's page (again); [attempt] 0 is the playlist address, 1 the web address. */
        data class Open(val attempt: Int) : Step()
        data class Done(val detail: String) : Step()
        data class Fail(val detail: String) : Step()
    }

    /** What the playlist's button says, read from the screen. */
    enum class State { NOT_OFFLINE, DOWNLOADING, OFFLINE, UNKNOWN }

    private var steps = 0
    private var tapped = 0
    private var started = false
    private var lookedWithoutButton = 0
    private var attempts = 0
    private var awayFromSpotify = 0
    private var lastTap = -10
    private var noSlot = false
    /** The state first seen on the playlist's page (null until a page showed a button): used to correct the phone's record. */
    var firstSeen: State? = null
        private set

    fun step(screenPkg: String, texts: List<String>): Step {
        steps++
        if (steps > (if (removing) MAX_REMOVE_STEPS else MAX_DOWNLOAD_STEPS)) return Step.Fail(if (removing) "Spotify did not remove the download in time" else "the download did not finish in time")
        val low = texts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        fun has(w: String) = low.any { it == w }
        fun contains(w: String) = low.any { it.contains(w) }

        if (noSlot) return Step.Fail(NO_SLOT)
        if (contains("out of offline devices")) {
            if (has("ok")) { noSlot = true; return Step.Tap("OK") }
            return Step.Fail(NO_SLOT)
        }
        if (screenPkg != SPOTIFY) {
            awayFromSpotify++
            if (started && awayFromSpotify >= 3) return Step.Fail("the screen left Spotify")
            return if (awayFromSpotify == 6 && attempts < 2) Step.Open(++attempts) else Step.Wait
        }
        awayFromSpotify = 0
        val state = when {
            low.any { it.startsWith("downloaded playlist:") } -> State.OFFLINE
            low.any { it.startsWith("downloading playlist:") } -> State.DOWNLOADING
            low.any { it.startsWith("download playlist:") } -> State.NOT_OFFLINE
            else -> State.UNKNOWN
        }
        if (state != State.UNKNOWN && firstSeen == null) firstSeen = state

        if (removing) {
            if (tapped > 0) {
                val confirm = listOf("remove download", "remove downloads", "remove", "yes").firstOrNull { has(it) }
                if (confirm != null) return Step.Tap(confirm)
            }
            return when (state) {
                State.NOT_OFFLINE -> Step.Done(if (tapped > 0) "download removed" else "already not downloaded")
                State.OFFLINE, State.DOWNLOADING -> when {
                    steps - lastTap < 3 -> Step.Wait // give the dialog and the removal time before pressing again
                    tapped >= 3 -> Step.Fail("Spotify would not remove the download")
                    else -> { tapped++; lastTap = steps; started = true; Step.Tap(if (state == State.OFFLINE) "Downloaded Playlist:" else "Downloading Playlist:") }
                }
                State.UNKNOWN -> missingButton()
            }
        }
        return when (state) {
            State.OFFLINE -> Step.Done(if (tapped > 0) "downloaded" else "already downloaded")
            State.DOWNLOADING -> { started = true; Step.Wait }
            State.NOT_OFFLINE -> if (tapped >= 3) Step.Fail("Spotify did not start the download") else { tapped++; started = true; Step.Tap("Download Playlist:") }
            State.UNKNOWN -> missingButton()
        }
    }

    private fun missingButton(): Step {
        lookedWithoutButton++
        if (lookedWithoutButton == 6 && attempts < 2) return Step.Open(++attempts)
        return if (lookedWithoutButton > 14) Step.Fail("Spotify's playlist page did not show a download button") else Step.Wait
    }

    companion object {
        const val SPOTIFY = "com.spotify.music"
        const val NO_SLOT = "Spotify has no free offline slot (free one in your Spotify account)"
        /** Ten minutes at one look every four seconds. */
        const val MAX_DOWNLOAD_STEPS = 150
        const val MAX_REMOVE_STEPS = 30
        const val LOOK_EVERY_MS = 4_000L
    }
}
