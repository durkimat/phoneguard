package io.github.durkimat.familychat

/**
 * What the group picker shows, with no screen in it: the search, the chosen groups (by id) and the A-to-Z list. Used by setup step 6 and
 * the admin screen alike. Nothing is chosen unless [chosen] says so.
 */
class PickerModel(all: List<Group>, chosen: List<Group> = emptyList()) {
    data class Row(val group: Group, val checked: Boolean, val hint: String?)

    private var all: List<Group> = sort(all)
    private val chosen = LinkedHashMap<String, Group>().also { m -> chosen.forEach { m[it.id] = it } }
    var query = ""

    val total get() = all.size
    /** Chosen groups, A to Z; ones the account has since left are still listed (so they can be removed). */
    fun chosenGroups(): List<Group> = sort(chosen.values.toList())
    fun isChosen(id: String) = id in chosen

    fun setAll(groups: List<Group>) {
        all = sort(groups)
        // keep the chosen names current
        for (g in all) if (g.id in chosen) chosen[g.id] = g
    }

    fun toggle(g: Group) { if (g.id in chosen) chosen.remove(g.id) else chosen[g.id] = g }
    fun remove(id: String) { chosen.remove(id) }

    /** The list for the current search (case-insensitive, anywhere in the name). A name two groups share gets a short hint (the end of the id). */
    fun rows(): List<Row> {
        val q = query.trim().lowercase()
        val shared = all.groupingBy { it.name.lowercase() }.eachCount()
        return all.filter { q.isEmpty() || it.name.lowercase().contains(q) }
            .map { Row(it, it.id in chosen, if ((shared[it.name.lowercase()] ?: 0) > 1) "ends ${it.id.takeLast(4)}" else null) }
    }

    private fun sort(l: List<Group>) = l.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
}
