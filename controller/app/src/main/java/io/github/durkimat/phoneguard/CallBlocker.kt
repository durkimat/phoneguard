package io.github.durkimat.phoneguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import io.github.durkimat.phoneguard.core.Engine

/** Hangs up on incoming calls while the current mode does not allow them (School: parents ring the school office). */
object CallBlocker {
    /** True when the mode in force right now blocks incoming calls. */
    fun blocking(c: Context): Boolean = !Engine.effective(Store.config(c), Store.boost(c), Clock.now()).inbound

    private var lastHangUp = 0L

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_RINGING) return
            if (!blocking(c)) return
            // Android announces one ringing call twice (with and without the number): hang up once
            val t = System.currentTimeMillis()
            if (t - lastHangUp < 3_000) return
            lastHangUp = t
            val ok = try { c.getSystemService(TelecomManager::class.java).endCall() } catch (e: Exception) { Log.log(c, "calls: endCall failed ${e.javaClass.simpleName}"); false }
            Log.log(c, "incoming call blocked by the mode, hung up=$ok")
        }
    }
}
