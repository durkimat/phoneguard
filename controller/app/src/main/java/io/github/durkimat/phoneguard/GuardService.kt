package io.github.durkimat.phoneguard

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings

/**
 * Keeps the process alive so we can react at once to new apps and to anyone switching grayscale off
 * (the rig showed the settings watcher only fires while our process is running).
 */
class GuardService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val evalSoon = Runnable { Controller.evaluate(this, "package change"); Link.runAsync(this, "package change") }

    private val packages = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Hiding an app can itself raise package events: coalesce so we never loop.
            main.removeCallbacks(evalSoon); main.postDelayed(evalSoon, 2_000)
        }
    }

    private val gray = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            if (Store.wantGray(this@GuardService) && !Enforcer.isGray(this@GuardService)) {
                val ok = Enforcer.setGray(this@GuardService, true)
                Log.log(this@GuardService, "grayscale switched off, re-asserted ok=$ok")
            }
        }
    }

    private val sound = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { SoundEnforcer.reassert(this@GuardService) }
    }

    /** After the screen is unlocked the phone shows our home screen, like a Nokia waking up (not whatever app, or the old stock launcher, was last in front). */
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try {
                val eff = io.github.durkimat.phoneguard.core.Engine.effective(Store.config(c), Store.boost(c), Clock.now())
                if (eff.lockdown.launcher == "nokia" && io.github.durkimat.phoneguard.incall.CallState.info == null)
                    c.startActivity(Intent(c, io.github.durkimat.phoneguard.launcher.LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (e: Exception) { Log.log(c, "home after unlock failed: ${e.javaClass.simpleName}") }
        }
    }

    private val powerEvents = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { PowerLog.sample(c, (i.action ?: "?").substringAfterLast('.').lowercase(), force = true) }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(
            Controller.NOTE_STATUS, Notifier.build(this, Store.summary(this)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        registerReceiver(
            packages,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
            },
            Context.RECEIVER_EXPORTED,
        )
        contentResolver.registerContentObserver(Settings.Secure.getUriFor(Enforcer.grayKey()), false, gray)
        registerReceiver(CallBlocker.receiver, IntentFilter(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        registerReceiver(sound, IntentFilter().apply {
            addAction(android.media.AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }, Context.RECEIVER_EXPORTED)
        registerReceiver(powerEvents, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, Context.RECEIVER_EXPORTED)
        registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_EXPORTED)
        PowerLog.sample(this, "service", force = true)
        Log.log(this, "service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Controller.evaluate(this, "service start")
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(packages)
        unregisterReceiver(sound)
        unregisterReceiver(powerEvents)
        unregisterReceiver(unlocked)
        unregisterReceiver(CallBlocker.receiver)
        contentResolver.unregisterContentObserver(gray)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(c: Context) {
            try { c.startForegroundService(Intent(c, GuardService::class.java)) }
            catch (e: Exception) { Log.log(c, "could not start service: ${e.javaClass.simpleName}") }
        }
    }
}
