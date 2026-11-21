package io.github.durkimat.wacore

/** The words of the replies written in the admin group (the bridge puts "» " in front of each). One place, so both apps say the same. */
object WaAcks {
    /** Bumped whenever the commands change: an upgraded phone tells the admin group once, when the number it last told about is lower. 1: add/remove group, groups; 2: photos on/off; 3: help. */
    const val COMMANDS_VERSION = 3

    /** The commands one app understands, one per line. [photos] is Family Chat's (Phone Guard has no photo switch). */
    fun commandLines(photos: Boolean): List<String> = listOf("add group <name>", "remove group <name>", "groups") + (if (photos) listOf("photos on", "photos off") else emptyList()) + "help"
    private fun commands(photos: Boolean) = commandLines(photos).joinToString("\n")

    fun usage(photos: Boolean = true) = "I did not understand. Use: " + commandLines(photos).dropLast(1).joinToString(", ") + " or help."
    /** What "help" says about the current settings, supplied by the app: a null line is not shown (Phone Guard has no photo or picture settings). */
    interface HelpStatus {
        val child: String
        val groups: List<String>
        val photosAllowed: Boolean? get() = null
        val wifiOnlyVideos: Boolean? get() = null
        val showMedia: Boolean? get() = null
        val readReceipts: Boolean? get() = null
        val connected: Boolean
        val version: String
    }

    /** The reply to "help": the commands on one line, then the settings at a glance, one plain line each (short enough for one WhatsApp message). */
    fun help(photos: Boolean = true, st: HelpStatus? = null): String {
        val head = "Commands: " + commandLines(photos).joinToString(", ")
        if (st == null) return head
        fun onOff(b: Boolean) = if (b) "on" else "off"
        val lines = listOfNotNull(
            canUse(st.child, st.groups),
            st.photosAllowed?.let { "Photos and videos: ${onOff(it)}" },
            st.wifiOnlyVideos?.let { "Videos download: ${if (it) "on Wi-Fi only" else "on any connection"}" },
            st.showMedia?.let { "Pictures, GIFs and stickers: ${if (it) "shown" else "hidden"}" },
            st.readReceipts?.let { "Tell WhatsApp when read: ${onOff(it)}" },
            "Connection: ${if (st.connected) "up" else "down"}",
            "Version: ${st.version}",
        )
        return (listOf(head) + lines).joinToString("\n").take(900)
    }

    /**
     * The one-time note after an upgrade: only when setup was finished before, the admin group is bound, the connection is up, and the commands changed
     * since the number last told (a fresh setup stores the current number, so it never gets this). Null otherwise.
     */
    fun upgradeNote(stored: Int, setupDone: Boolean, adminBound: Boolean, connected: Boolean, versionName: String): String? =
        if (setupDone && adminBound && connected && stored < COMMANDS_VERSION) "Updated to $versionName. New: photos on, photos off, help. Send help for all commands." else null
    fun added(name: String) = "Added: $name"
    fun removed(name: String) = "Removed: $name"
    fun already(name: String) = "Already allowed: $name"
    fun noGroup(name: String) = "No group called $name"
    fun nothingToChoose() = "Nothing to choose."
    fun canUse(child: String, names: List<String>) = if (names.isEmpty()) "$child can use no groups yet." else "$child can use: " + names.joinToString(", ")
    fun photos(on: Boolean) = "Photos and videos: ${if (on) "on" else "off"}"
    fun notSupported() = "Not supported here"
    fun ready(photos: Boolean = true) = "Ready. Commands:\n" + commands(photos)

    /** Several groups share [name]: a numbered list ("1) Football (ends 1a2b)") and the way to answer. [options] are (id, name). */
    fun many(name: String, options: List<Pair<String, String>>): String {
        val count = when (options.size) { 2 -> "Two"; 3 -> "Three"; 4 -> "Four"; 5 -> "Five"; else -> options.size.toString() }
        return "$count groups are called $name:\n" + options.mapIndexed { i, o -> "${i + 1}) ${o.second} (ends ${o.first.takeLast(4)})" }.joinToString("\n") + "\nReply with a number."
    }
}
