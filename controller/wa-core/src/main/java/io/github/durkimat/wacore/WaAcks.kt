package io.github.durkimat.wacore

/** The words of the replies written in the admin group (the bridge puts "» " in front of each). One place, so both apps say the same. */
object WaAcks {
    /** Bumped whenever the commands change: an upgraded phone tells the admin group once, when the number it last told about is lower. 1: add/remove group, groups; 2: photos on/off; 3: help. */
    const val COMMANDS_VERSION = 3

    /** The commands one app understands, one per line. [photos] is Family Chat's (Phone Guard has no photo switch). */
    fun commandLines(photos: Boolean): List<String> = listOf("add group <name>", "remove group <name>", "groups") + (if (photos) listOf("photos on", "photos off") else emptyList()) + "help"
    private fun commands(photos: Boolean) = commandLines(photos).joinToString("\n")

    fun usage(photos: Boolean = true) = "I did not understand. Use: " + commandLines(photos).dropLast(1).joinToString(", ") + " or help."
    /** The reply to "help": the same list as [ready]. */
    fun help(photos: Boolean = true) = "Commands:\n" + commands(photos)

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
