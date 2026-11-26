package io.github.durkimat.phoneguard.net

import android.content.Context
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.core.RadioEntry
import org.json.JSONArray
import java.io.File

/**
 * The radio diary: a line each time the radios, the lock or the reason changes, kept on the phone for three days and sent to the
 * parent with each sync, so they can see that the radios really do go off. See [RadioEntry] for what a line says.
 */
object RadioDiary {
    private const val KEEP_MS = 3L * 24 * 3600 * 1000
    private const val MAX_LINES = 2000
    private const val MAX_UPLOAD = 300

    private fun dir(c: Context) = c.createDeviceProtectedStorageContext()
    private fun file(c: Context) = File(dir(c).filesDir, "radio.log")
    private fun sp(c: Context) = dir(c).getSharedPreferences("radiodiary", Context.MODE_PRIVATE)

    private fun parse(line: String): RadioEntry? = try { RadioEntry.fromJson(JSONArray(line)) } catch (_: Exception) { null }

    @Synchronized
    fun note(c: Context, e: RadioEntry) {
        try {
            val f = file(c)
            val lines = if (f.exists()) f.readLines() else emptyList()
            if (!e.differsFrom(lines.lastOrNull()?.let { parse(it) })) return
            val keep = lines.filter { l -> parse(l)?.let { it.t >= e.t - KEEP_MS } == true }.takeLast(MAX_LINES - 1)
            f.writeText((keep + e.toJson().toString()).joinToString("\n", postfix = "\n"))
        } catch (x: Exception) { Log.log(c, "radio diary: ${x.javaClass.simpleName}") }
    }

    /** Lines not yet sent (oldest first, at most [MAX_UPLOAD]); call [commit] once the other end has answered. */
    @Synchronized
    fun pending(c: Context): JSONArray {
        val out = JSONArray()
        try {
            val p = sp(c); val since = p.getLong("uploaded", 0)
            var last = since
            file(c).takeIf { it.exists() }?.forEachLine { l ->
                val e = parse(l)
                if (e != null && e.t > since && out.length() < MAX_UPLOAD) { out.put(e.toJson()); last = e.t }
            }
            p.edit().putLong("inflight", last).apply()
        } catch (x: Exception) { Log.log(c, "radio diary read failed: ${x.javaClass.simpleName}") }
        return out
    }

    @Synchronized
    fun commit(c: Context) { val p = sp(c); p.edit().putLong("uploaded", p.getLong("inflight", p.getLong("uploaded", 0))).apply() }
}
