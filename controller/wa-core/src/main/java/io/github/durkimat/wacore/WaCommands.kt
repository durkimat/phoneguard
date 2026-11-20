package io.github.durkimat.wacore

/** A command typed in the admin group. The grammar is deliberately tiny. */
sealed class WaCommand {
    data class AddGroup(val name: String) : WaCommand()
    data class RemoveGroup(val name: String) : WaCommand()
    /** Read only: which groups are allowed now. */
    object ListAllowed : WaCommand()
    /** A number, the answer to "which one?". */
    data class Choose(val n: Int) : WaCommand()
    /** "photos on" / "photos off": whether the child may send photos and videos (Family Chat only). */
    data class Photos(val on: Boolean) : WaCommand()
    /** "help": the list of commands. */
    object Help : WaCommand()
    object Unknown : WaCommand()
}

/** What a group name matched among the account's joined groups. */
sealed class WaMatch {
    object None : WaMatch()
    data class One(val id: String, val name: String) : WaMatch()
    data class Many(val options: List<Pair<String, String>>) : WaMatch()
}

/** The command parser, shared by both apps so they behave the same: "add group <name>", "remove group <name>", "groups", and a number to pick from a list. */
object WaCommands {
    /** The one place the admin group name prefix lives: the group is "<prefix><child name>", e.g. FamilyChat-Sam. Both apps use it; pass another prefix if Phone Guard should get its own. */
    const val ADMIN_PREFIX = "FamilyChat-"

    fun adminGroupName(child: String, prefix: String = ADMIN_PREFIX) = prefix + child.trim()

    fun parse(text: String): WaCommand {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return WaCommand.Unknown
        if (t.all { it.isDigit() } && t.length <= 3) return WaCommand.Choose(t.toInt())
        val lower = t.lowercase()
        return when {
            lower == "groups" -> WaCommand.ListAllowed
            lower == "help" -> WaCommand.Help
            lower == "photos on" -> WaCommand.Photos(true)
            lower == "photos off" -> WaCommand.Photos(false)
            lower.startsWith("add group ") && t.length > 10 -> WaCommand.AddGroup(t.substring(10).trim())
            lower.startsWith("remove group ") && t.length > 13 -> WaCommand.RemoveGroup(t.substring(13).trim())
            else -> WaCommand.Unknown
        }
    }

    /** Finds a group by name (ignoring case) among the "id|name" lines [groups] returns; the admin group itself is never offered. */
    fun match(name: String, groups: String, exceptId: String = ""): WaMatch {
        val want = name.trim().lowercase()
        val hits = groups.lineSequence().mapNotNull { line ->
            val i = line.indexOf('|'); if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.filter { it.first != exceptId.removeSuffix("@g.us") && it.second.trim().lowercase() == want }.toList()
        return when (hits.size) { 0 -> WaMatch.None; 1 -> WaMatch.One(hits[0].first, hits[0].second); else -> WaMatch.Many(hits) }
    }

    /** The groups an admin group may be: those whose name starts with [prefix] ([ADMIN_PREFIX], "FamilyChat-"), as (id, name) pairs. */
    fun adminCandidates(groups: String, prefix: String = ADMIN_PREFIX): List<Pair<String, String>> = groups.lineSequence().mapNotNull { line ->
        val i = line.indexOf('|'); if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
    }.filter { it.second.startsWith(prefix, ignoreCase = true) }.toList()
}
