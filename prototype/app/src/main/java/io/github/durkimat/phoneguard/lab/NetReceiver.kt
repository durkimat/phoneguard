package io.github.durkimat.phoneguard.lab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Drives the network test from adb, so no URL has to be typed on the phone:
 *   adb shell am broadcast -a io.github.durkimat.phoneguard.lab.NET -n <pkg>/.NetReceiver \
 *     --es url https://... --es method POST --ei kb 1 --ei n 1 --ei gap 5000
 */
class NetReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val url = i.getStringExtra("url")
        if (url.isNullOrBlank()) return LabLog.log(c, "NET: no url extra")
        val method = (i.getStringExtra("method") ?: "POST").uppercase()
        val kb = i.getIntExtra("kb", 1).coerceIn(1, 512)
        val n = i.getIntExtra("n", 1).coerceIn(1, 20)
        val gap = i.getIntExtra("gap", 5000).coerceAtLeast(1000)
        val pending = goAsync()
        thread {
            try {
                for (k in 1..n) {
                    once(c, url, method, kb, k)
                    if (k < n) Thread.sleep(gap.toLong())
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun once(c: Context, url: String, method: String, kb: Int, seq: Int) {
        val body = "{\"t\":\"lab\",\"seq\":$seq,\"pad\":\"" + "x".repeat((kb * 1024) - 30) + "\"}"
        val t0 = SystemClock.elapsedRealtime()
        try {
            val h = URL(url).openConnection() as HttpURLConnection
            h.connectTimeout = 15000; h.readTimeout = 15000
            h.requestMethod = method
            h.setRequestProperty("X-PG-Lab", "header-forward-test")
            if (method == "POST") {
                h.doOutput = true
                h.setRequestProperty("Content-Type", "application/json")
                h.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = h.responseCode
            val resp = (if (code < 400) h.inputStream else h.errorStream)?.readBytes() ?: ByteArray(0)
            val hdrs = h.headerFields.filterKeys { it != null }.entries.joinToString("; ") { "${it.key}=${it.value.joinToString(",")}" }
            LabLog.log(c, "NET#$seq $method ${if (method == "POST") "${body.length}B" else ""} -> $code in ${SystemClock.elapsedRealtime() - t0} ms, reply ${resp.size}B: ${String(resp).take(200)} | headers: ${hdrs.take(400)}")
        } catch (e: Exception) {
            LabLog.log(c, "NET#$seq failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
