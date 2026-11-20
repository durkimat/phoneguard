package io.github.durkimat.familychat

/** A WhatsApp group the parent is in. The id is what the app keeps; the name is only for people. */
data class Group(val id: String, val name: String) {
    companion object {
        /** The transport's "id|name" lines (id without @g.us), skipping blank names. */
        fun parse(lines: String): List<Group> = lines.lineSequence().mapNotNull { l ->
            val i = l.indexOf('|'); if (i <= 0) null else Group(l.substring(0, i).removeSuffix("@g.us").trim(), l.substring(i + 1).trim()).takeIf { it.name.isNotEmpty() && it.id.isNotEmpty() }
        }.distinctBy { it.id }.toList()
        fun format(gs: List<Group>) = gs.joinToString("\n") { "${it.id}|${it.name}" }
    }
}
