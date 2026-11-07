package io.github.durkimat.phoneguard.whatsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The periodic WhatsApp check (see [WhatsApp.poll]). */
class WaAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pending = goAsync()
        Thread { try { WhatsApp.poll(c) } finally { pending.finish() } }.start()
    }
}
