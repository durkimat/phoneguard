package io.github.durkimat.wacore

/** The words of the replies written in the admin group (the bridge puts "» " in front of each). One place, so both apps say the same. */
object WaAcks {
    fun usage() = "I did not understand. Use: add group <name>, remove group <name>, or groups."
    fun added(name: String) = "Added: $name"
    fun removed(name: String) = "Removed: $name"
    fun already(name: String) = "Already allowed: $name"
    fun noGroup(name: String) = "No group called $name"
    fun nothingToChoose() = "Nothing to choose."
    fun canUse(child: String, names: List<String>) = if (names.isEmpty()) "$child can use no groups yet." else "$child can use: " + names.joinToString(", ")
    fun ready() = "Ready. Commands: add group <name>, remove group <name>, groups."

    /** Several groups share [name]: a numbered list ("1) Football (ends 1a2b)") and the way to answer. [options] are (id, name). */
    fun many(name: String, options: List<Pair<String, String>>): String {
        val count = when (options.size) { 2 -> "Two"; 3 -> "Three"; 4 -> "Four"; 5 -> "Five"; else -> options.size.toString() }
        return "$count groups are called $name:\n" + options.mapIndexed { i, o -> "${i + 1}) ${o.second} (ends ${o.first.takeLast(4)})" }.joinToString("\n") + "\nReply with a number."
    }
}
