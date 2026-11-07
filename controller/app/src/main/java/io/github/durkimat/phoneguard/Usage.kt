package io.github.durkimat.phoneguard

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Foreground time per app per day, from the system's usage events. Sent to HA as seconds only:
 * no content, no titles. Needs the "usage access" app-op, granted at setup (spec section 5, Q8).
 */
object Usage {
    /** Not "screen time": the home screen and system plumbing. */
    private val skip = setOf("com.android.systemui", "android", "com.android.permissioncontroller", "com.android.settings.intelligence")

    fun allowed(c: Context): Boolean = try {
        c.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    private fun midnight(offsetDays: Int): Long = Calendar.getInstance().run {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, offsetDays); timeInMillis
    }

    /** {"2026-11-07": {"pkg": seconds}} for today and yesterday, or null when usage access is not granted. */
    fun recent(c: Context): JSONObject? {
        if (!allowed(c)) return null
        val out = JSONObject()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.UK)
        val now = System.currentTimeMillis()
        for (back in 0..1) {
            val start = midnight(-back)
            val end = minOf(now, midnight(-back + 1))
            val day = JSONObject()
            forRange(c, start, end).forEach { (pkg, ms) -> if (ms >= 5_000) day.put(pkg, ms / 1000) }
            out.put(fmt.format(start), day)
        }
        return out
    }

    fun forRange(c: Context, start: Long, end: Long): Map<String, Long> {
        val ev = c.getSystemService(UsageStatsManager::class.java).queryEvents(start, end)
        val e = UsageEvents.Event()
        val open = HashMap<String, Long>()
        val total = HashMap<String, Long>()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> open.putIfAbsent(pkg, e.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    open.remove(pkg)?.let { total.merge(pkg, e.timeStamp - it, Long::plus) }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    open.forEach { (p, s) -> total.merge(p, e.timeStamp - s, Long::plus) }
                    open.clear()
                }
            }
        }
        open.forEach { (p, s) -> total.merge(p, end - s, Long::plus) } // still in the foreground
        val home = Protected.dynamic(c).firstOrNull { it != c.packageName && c.packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName == it }
        return total.filterKeys { it !in skip && it != c.packageName && it != home }
    }
}
