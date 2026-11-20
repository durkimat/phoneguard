package io.github.durkimat.familychat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings as AndroidSettings

/**
 * Keeps the connection to WhatsApp alive. A foreground service with a quiet, low-priority notification (the one Android insists on);
 * the battery exemption below is asked for during setup, in the app's own words, never by showing the system dialog unprompted.
 */
class ConnectionService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Family Chat is connected").setOngoing(true).build()
        startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val NOTE = 1
        fun start(c: Context) = c.startForegroundService(Intent(c, ConnectionService::class.java))
        fun stop(c: Context) { c.stopService(Intent(c, ConnectionService::class.java)) }
        fun batteryExempt(c: Context) = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
        /** The intent that asks for the exemption for this app only; the screen launches it when the parent taps "keep it connected". */
        fun batteryExemptionIntent(c: Context): Intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}"))
    }
}
