package io.github.durkimat.familychat

import io.github.durkimat.wacore.WaAcks
import io.github.durkimat.wacore.WaCommand
import io.github.durkimat.wacore.WaCommands
import io.github.durkimat.wacore.WaMatch

/**
 * Takes the parent's chat commands and changes the allowed groups on this phone. Family Chat has no companion, so the change is local and
 * final. Returns the reply to write in the admin group. [groups] gives the account's groups (the transport's "id|name" lines).
 */
class AdminBrain(
    private val child: () -> String,
    private val adminId: () -> String,
    private val allowed: () -> List<Group>,
    private val setAllowed: (List<Group>) -> Unit,
    private val groups: () -> String,
    private val setPhotos: (Boolean) -> Unit = {},
) {
    private var choice: Pair<Boolean, List<Group>>? = null // (add?, the groups that share the name)

    @Synchronized fun handle(text: String): String = when (val cmd = WaCommands.parse(text)) {
        is WaCommand.Unknown -> { choice = null; WaAcks.usage() }
        is WaCommand.ListAllowed -> { choice = null; WaAcks.canUse(child(), allowed().map { it.name }.sortedBy { it.lowercase() }) }
        is WaCommand.Photos -> { choice = null; setPhotos(cmd.on); WaAcks.photos(cmd.on) }
        is WaCommand.AddGroup -> change(true, cmd.name, groups())
        is WaCommand.RemoveGroup -> change(false, cmd.name, Group.format(allowed()))
        is WaCommand.Choose -> {
            val c = choice; choice = null
            val pick = c?.second?.getOrNull(cmd.n - 1)
            if (c == null || pick == null) WaAcks.nothingToChoose() else apply(c.first, pick)
        }
    }

    private fun change(add: Boolean, name: String, lines: String): String {
        choice = null
        return when (val m = WaCommands.match(name, lines, adminId())) {
            is WaMatch.None -> WaAcks.noGroup(name)
            is WaMatch.One -> apply(add, Group(m.id, m.name))
            is WaMatch.Many -> { choice = add to m.options.map { Group(it.first, it.second) }; WaAcks.many(name, m.options) }
        }
    }

    private fun apply(add: Boolean, g: Group): String {
        val now = allowed()
        return if (add) {
            if (now.any { it.id == g.id }) WaAcks.already(g.name) else { setAllowed(now + g); WaAcks.added(g.name) }
        } else {
            if (now.none { it.id == g.id }) WaAcks.noGroup(g.name) else { setAllowed(now.filter { it.id != g.id }); WaAcks.removed(g.name) }
        }
    }
}
