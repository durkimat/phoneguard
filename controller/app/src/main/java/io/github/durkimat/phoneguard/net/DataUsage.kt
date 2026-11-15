package io.github.durkimat.phoneguard.net

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.core.DataPlan
import io.github.durkimat.phoneguard.core.DataView
import org.json.JSONObject

/**
 * How much of the SIM's mobile data has been used this period (the phone's own count: Wi-Fi does not count), against the allowance the owner entered
 * on the phone. The carrier's count is the one that matters, so the plan has a correction. Needs the usage-access permission and, for the SIM's subscriber id,
 * READ_PRIVILEGED_PHONE_STATE (the system app has both); without them nothing is shown rather than a wrong number.
 */
object DataUsage {
    private fun sp(c: Context) = c.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("dataplan", Context.MODE_PRIVATE)

    fun plan(c: Context): DataPlan {
        val p = sp(c)
        return DataPlan(p.getLong("allowance", 0), p.getInt("renewDay", 1), p.getString("start", "") ?: "", p.getInt("warnPct", 10), p.getLong("correctionMb", 0), p.getInt("subId", -1))
    }

    fun save(c: Context, plan: DataPlan) {
        sp(c).edit().putLong("allowance", plan.allowance).putInt("renewDay", plan.renewDay.coerceIn(1, 28)).putString("start", plan.start).putInt("warnPct", plan.warnPct.coerceIn(1, 50))
            .putLong("correctionMb", plan.correctionMb).putInt("subId", plan.subId).apply()
        cached = null
    }

    /** The SIMs in the phone as (subscription id, name for the list); empty without a SIM or without permission. */
    fun sims(c: Context): List<Pair<Int, String>> = try {
        c.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty().map { it.subscriptionId to ("SIM ${it.simSlotIndex + 1}" + (it.carrierName?.toString()?.takeIf { n -> n.isNotBlank() }?.let { n -> " $n" } ?: "")) }
    } catch (_: Exception) { emptyList() }

    private fun subId(c: Context, plan: DataPlan): Int {
        if (plan.subId >= 0 && sims(c).any { it.first == plan.subId }) return plan.subId
        return SubscriptionManager.getDefaultDataSubscriptionId()
    }

    /** Bytes of mobile data used by [subscription] from [from] to [to], or null when it cannot be read. */
    fun bytes(c: Context, subscription: Int, from: Long, to: Long): Long? = try {
        val tm = c.getSystemService(TelephonyManager::class.java).let { if (subscription >= 0) it.createForSubscriptionId(subscription) else it }
        val id = try { tm.subscriberId } catch (_: SecurityException) { null }
        val nsm = c.getSystemService(NetworkStatsManager::class.java)
        val b = nsm.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, id, from, to)
        b.rxBytes + b.txBytes
    } catch (e: Exception) { Log.log(c, "data usage: not readable: ${e.javaClass.simpleName}"); null }

    private class Cache(val at: Long, val view: DataView?, val since: Long, val until: Long)
    @Volatile private var cached: Cache? = null

    /** The numbers for the home screen and the Data page, or null when there is no plan or no SIM. Cached for a minute. Call off the main thread. */
    fun view(c: Context, now: Long = System.currentTimeMillis()): DataView? = current(c, now)?.view

    private fun current(c: Context, now: Long): Cache? {
        cached?.let { if (now - it.at < 60_000L && now >= it.at) return it }
        val plan = plan(c)
        val r = if (!plan.set) Cache(now, null, 0, 0) else {
            val (from, to) = plan.period(now)
            val used = bytes(c, subId(c, plan), from, now)
            Cache(now, used?.let { DataView((it + plan.correctionMb * 1_000_000L).coerceAtLeast(0), plan.allowance, plan.warnPct) }, from, to)
        }
        cached = r
        return r
    }

    /** For the sync state: totals only. Null when there is nothing to report. */
    fun state(c: Context): JSONObject? {
        val cur = current(c, System.currentTimeMillis()) ?: return null
        val v = cur.view ?: return null
        return JSONObject().put("used", v.used).put("allowance", v.allowance).put("since", cur.since).put("until", cur.until)
    }

    /** When the period ends (the next renewal), epoch milliseconds, or 0. */
    fun renewal(c: Context): Long = current(c, System.currentTimeMillis())?.until ?: 0L
}
