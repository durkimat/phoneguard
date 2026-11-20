package io.github.durkimat.phoneguard.whatsapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Changes the parent made by message in the admin group, kept until the companion (the Worker) has them. The phone applies a change at once
 * (the allow list the bridge uses is the config's list with these on top), records it with its time and the config version it was made on,
 * and sends it in the next sync. The Worker's answer says for each one whether it was applied, superseded by a newer companion edit or
 * rejected; only then is it dropped, so a config arriving first can never undo it for a moment.
 */
object WaPending {
    data class Op(val op: String, val id: String, val name: String, val at: Long, val base: Int, val cmdTs: Long) {
        fun toJson(): JSONObject = JSONObject().put("op", op).put("id", id).put("name", name).put("at", at).put("base", base).put("cmdTs", cmdTs)
        companion object { fun of(j: JSONObject) = Op(j.optString("op"), j.optString("id"), j.optString("name"), j.optLong("at"), j.optInt("base"), j.optLong("cmdTs")) }
    }

    const val ADD = "add_group"
    const val REMOVE = "remove_group"

    /** The allow entries with the pending changes on top: an add puts "gid:<id>" in, a remove takes that id (and an older "g:<name>" entry for the same group) out. */
    fun overlay(allowed: List<String>, ops: List<Op>): List<String> {
        val out = allowed.toMutableList()
        for (o in ops) {
            val gid = "gid:" + o.id
            when (o.op) {
                ADD -> if (out.none { it.equals(gid, ignoreCase = true) }) out.add(gid)
                REMOVE -> out.removeAll { it.equals(gid, ignoreCase = true) || (o.name.isNotBlank() && it.equals("g:" + o.name, ignoreCase = true)) }
            }
        }
        return out
    }

    /** What the Worker said about the changes we sent: [results] maps a change's cmdTs to "applied", "superseded" or "rejected". Answered changes are dropped; the rest stay. */
    fun reconcile(ops: List<Op>, results: Map<Long, String>): Pair<List<Op>, List<Pair<Op, String>>> {
        val answered = ops.filter { results.containsKey(it.cmdTs) }.map { it to results.getValue(it.cmdTs) }
        return ops.filter { !results.containsKey(it.cmdTs) } to answered
    }

    /**
     * One whole sync, in the order it happens: the request carried [ops]; the reply carries [results] and the new config's allow entries. The allow list
     * the phone ends with, and the changes still waiting. Pure, so the ordering is tested: whatever the config says, a change the Worker has not answered yet stays on top.
     */
    fun afterSync(ops: List<Op>, results: Map<Long, String>, configAllowed: List<String>): Pair<List<String>, List<Op>> {
        val (left, _) = reconcile(ops, results)
        return overlay(configAllowed, left) to left
    }

    // ---- what the phone keeps
    private fun prefs(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("wa_admin", Context.MODE_PRIVATE)

    @Synchronized fun pending(c: Context): List<Op> = try {
        val a = JSONArray(prefs(c).getString("pending", "[]")); (0 until a.length()).map { Op.of(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    @Synchronized fun setPending(c: Context, ops: List<Op>) { prefs(c).edit().putString("pending", JSONArray(ops.map { it.toJson() }).toString()).apply() }

    @Synchronized fun add(c: Context, op: Op) { setPending(c, pending(c).filter { !(it.op == op.op && it.id == op.id) } + op) }

    /** The newest command already applied (the bridge ignores older ones). */
    fun lastCommand(c: Context): Long = prefs(c).getLong("last", 0L)
    fun setLastCommand(c: Context, ts: Long) { prefs(c).edit().putLong("last", ts).apply() }

    /** The sync request's WhatsApp part: the queued changes, and the groups the phone is in (for the companion's pick-list). */
    fun payload(c: Context, groupsAt: Long, groups: List<Pair<String, String>>): JSONObject = JSONObject()
        .put("changes", JSONArray(pending(c).map { it.toJson() }))
        .put("groups", JSONArray(groups.map { JSONObject().put("id", it.first).put("name", it.second) })).put("groupsAt", groupsAt)

    /** The reply's "waResults": [{cmdTs, status}] to a map. */
    fun parseResults(a: JSONArray?): Map<Long, String> = if (a == null) emptyMap() else (0 until a.length()).associate { a.getJSONObject(it).optLong("cmdTs") to a.getJSONObject(it).optString("status") }

    fun cachedGroups(c: Context): List<Pair<String, String>> = try {
        val a = JSONArray(prefs(c).getString("groups", "[]")); (0 until a.length()).map { a.getJSONObject(it).optString("id") to a.getJSONObject(it).optString("name") }
    } catch (_: Exception) { emptyList() }
    fun cacheGroups(c: Context, lines: String) {
        val list = lines.lineSequence().mapNotNull { l -> val i = l.indexOf('|'); if (i <= 0) null else l.substring(0, i) to l.substring(i + 1) }.toList()
        prefs(c).edit().putString("groups", JSONArray(list.map { JSONObject().put("id", it.first).put("name", it.second) }).toString()).putLong("groupsAt", System.currentTimeMillis()).apply()
    }
    fun groupsAt(c: Context): Long = prefs(c).getLong("groupsAt", 0L)
}
