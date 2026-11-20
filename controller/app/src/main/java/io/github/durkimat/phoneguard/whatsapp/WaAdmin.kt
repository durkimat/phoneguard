package io.github.durkimat.phoneguard.whatsapp

import android.content.Context
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.Store
import io.github.durkimat.phoneguard.companion.WorkerLink
import io.github.durkimat.wacore.WaCommand
import io.github.durkimat.wacore.WaCommands
import io.github.durkimat.wacore.WaMatch

/**
 * The parent's chat commands, read in the admin group (see the bridge's rule for what counts as a command): "add group <name>", "remove group <name>",
 * "groups", and a number to choose between groups of one name. A change is applied at once, queued for the Worker ([WaPending]) and acknowledged in the
 * admin group. Commands change only the WhatsApp allow list: nothing here touches any other setting.
 */
object WaAdmin {
    private var choice: Pair<String, List<Pair<String, String>>>? = null

    fun onCommand(c: Context, text: String, ts: Long) { Thread { try { handle(c.applicationContext, text, ts) } catch (e: Throwable) { Log.log(c, "whatsapp: admin command failed ${e.javaClass.simpleName}") } }.start() }

    @Synchronized private fun handle(c: Context, text: String, ts: Long) {
        val b = WhatsApp.open(c) ?: return
        WaPending.setLastCommand(c, ts)
        val adminId = Store.config(c)?.whatsapp?.adminGroup.orEmpty()
        fun ack(t: String) { try { b.sendAdmin(t) } catch (e: Throwable) { Log.log(c, "whatsapp: admin ack failed ${e.javaClass.simpleName}") } }
        when (val cmd = WaCommands.parse(text)) {
            is WaCommand.Unknown -> ack("I did not understand. Use: add group <name>, remove group <name>, or groups.")
            is WaCommand.ListAllowed -> ack(allowedLine(c, b.groups()))
            is WaCommand.Photos -> ack(io.github.durkimat.wacore.WaAcks.notSupported()) // Family Chat only: here it changes nothing
            is WaCommand.AddGroup -> change(c, b.groups(), WaPending.ADD, cmd.name, adminId, ts, ::ack)
            is WaCommand.RemoveGroup -> change(c, b.groups(), WaPending.REMOVE, cmd.name, adminId, ts, ::ack)
            is WaCommand.Choose -> {
                val pending = choice; choice = null
                val pick = pending?.second?.getOrNull(cmd.n - 1)
                if (pending == null || pick == null) ack("Nothing to choose.") else apply(c, pending.first, pick.first, pick.second, ts, ::ack)
            }
        }
    }

    private fun change(c: Context, groups: String, op: String, name: String, adminId: String, ts: Long, ack: (String) -> Unit) {
        WaPending.cacheGroups(c, groups)
        when (val m = WaCommands.match(name, groups, adminId)) {
            is WaMatch.None -> { choice = null; ack("No group called \"$name\".") }
            is WaMatch.One -> { choice = null; apply(c, op, m.id, m.name, ts, ack) }
            is WaMatch.Many -> { choice = op to m.options; ack("More than one group is called \"$name\". Reply with a number:\n" + m.options.mapIndexed { i, o -> "${i + 1}. ${o.second} (ends ${o.first.takeLast(4)})" }.joinToString("\n")) }
        }
    }

    private fun apply(c: Context, op: String, id: String, name: String, ts: Long, ack: (String) -> Unit) {
        WaPending.add(c, WaPending.Op(op, id, name, System.currentTimeMillis(), WorkerLink.cfgVersion(c), ts))
        WhatsApp.refreshAllowed(c)
        ack(if (op == WaPending.ADD) "Added $name." else "Removed $name.")
        Thread { try { WorkerLink.sync(c, "admin command") } catch (_: Throwable) { } }.start() // the Worker hears about it on this very sync
    }

    private fun allowedLine(c: Context, groups: String): String {
        WaPending.cacheGroups(c, groups)
        val names = groups.lineSequence().mapNotNull { l -> val i = l.indexOf('|'); if (i <= 0) null else l.substring(0, i) to l.substring(i + 1) }.toMap()
        val w = Store.config(c)?.whatsapp
        val entries = if (w == null) emptyList() else WhatsApp.effectiveAllowed(c, w)
        val out = entries.filter { it.startsWith("gid:", true) || it.startsWith("g:", true) }.map { e -> if (e.startsWith("gid:", true)) names[e.substring(4).removeSuffix("@g.us").lowercase()] ?: names[e.substring(4)] ?: "(a group)" else e.substring(2) }
        return if (out.isEmpty()) "No groups are allowed yet." else "Allowed groups: " + out.joinToString(", ")
    }

    /** The Worker's answers to the changes sent in the last sync: answered changes are dropped; a change that was not applied is said so in the admin group. */
    fun onResults(c: Context, results: Map<Long, String>) {
        if (results.isEmpty()) return
        val (left, answered) = WaPending.reconcile(WaPending.pending(c), results)
        WaPending.setPending(c, left)
        WhatsApp.refreshAllowed(c)
        val notApplied = answered.filter { it.second != "applied" }
        if (notApplied.isNotEmpty()) Thread { try { WhatsApp.open(c)?.sendAdmin("Not applied: " + notApplied.joinToString(", ") { "${it.first.name} (${if (it.second == "superseded") "changed on the companion since" else "refused"})" }) } catch (_: Throwable) { } }.start()
    }
}
