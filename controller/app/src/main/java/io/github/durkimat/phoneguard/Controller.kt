package io.github.durkimat.phoneguard

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.durkimat.phoneguard.core.Engine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The single place that turns (config, boost, time) into a phone state. Call evaluate() on any trigger. */
object Controller {
    const val CH_STATUS = "status"
    const val CH_WARN = "warnings"
    const val NOTE_STATUS = 1
    const val NOTE_WARN = 2

    private val HM = SimpleDateFormat("EEE HH:mm", Locale.UK)

    @Synchronized
    fun evaluate(c: Context, reason: String) {
        val app = c.applicationContext
        if (!Dpm.isOwner(app)) {
            Log.log(app, "EVAL $reason: not device owner, nothing enforced")
            Store.summary(app, "Not device owner")
            Notifier.status(app, "Not set up as device owner")
            return
        }
        Enforcer.grantOwnPermissions(app)
        Enforcer.ensureSideButton(app)
        Enforcer.ensureNoteListener(app)
        PowerLog.sample(app, "eval")
        val now = Clock.now()
        val cfg = Store.config(app)
        var boost = Store.boost(app)
        if (boost != null && now >= boost.until) { Store.setBoost(app, null); boost = null }

        val eff = Engine.effective(cfg, boost, now)
        val res = Enforcer.applyApps(app, cfg, eff.allowed, hideStockHome = eff.lockdown.launcher == "nokia" && Enforcer.weAreHome(app))
        Enforcer.applyRestrictions(app, cfg)
        Enforcer.protectProcesses(app)
        Enforcer.setGray(app, eff.gray)
        LockdownEnforcer.apply(app, eff.lockdown, Enforcer.launcherPackages(app))
        LocationLock.set(app, off = eff.location == "off")
        LocationReporter.apply(app, eff.location, eff.locEvery)
        // notifications: on or off for this mode, and the apps HA hides; run the current ones through the rules again when that changed
        val hide = cfg?.notesHide ?: emptySet()
        if (io.github.durkimat.phoneguard.notes.Notes.enabled != (eff.notes == "show") || io.github.durkimat.phoneguard.notes.Notes.extraHidden != hide) {
            io.github.durkimat.phoneguard.notes.Notes.enabled = eff.notes == "show"; io.github.durkimat.phoneguard.notes.Notes.extraHidden = hide
            io.github.durkimat.phoneguard.notes.NoteService.instance?.reapply()
        }
        val soundNote = SoundEnforcer.apply(app, eff.sound)
        try { io.github.durkimat.phoneguard.whatsapp.WhatsApp.apply(app, cfg, eff) } catch (e: Throwable) { Log.log(app, "whatsapp apply failed: ${e.javaClass.simpleName}") }

        // Warning before a change that removes apps, and the next alarm.
        val change = Engine.nextChange(cfg, boost, now)
        val warn = Engine.warningFor(cfg, boost, now, res.manageable)
        var alarmAt = now + 30 * 60_000L // safety re-check at least every 30 minutes
        if (change != null) alarmAt = minOf(alarmAt, change.first)
        var warnNote = ""
        if (warn != null) {
            val warnTime = warn.first - Engine.WARN_MS
            if (Store.lastWarned(app) != warn.first) {
                if (now >= warnTime) {
                    Notifier.warn(app, "Phone changes to ${Engine.modeDef(cfg, warn.second.modeId).name} at ${HM.format(Date(warn.first)).takeLast(5)}",
                        "Some apps will close. Save what you are doing.")
                    Store.setLastWarned(app, warn.first)
                    warnNote = " WARNED"
                } else {
                    alarmAt = minOf(alarmAt, warnTime)
                }
            }
        }
        arm(app, alarmAt)

        val modeName = Engine.modeDef(cfg, eff.modeId).name + (if (boost != null) " + boost" else "")
        val until = change?.let { HM.format(Date(it.first)) } ?: "no change planned"
        val line = "$modeName until $until"
        Store.summary(app, line)
        Notifier.status(app, line)
        Log.log(app, "EVAL $reason: mode=${eff.modeId}${if (boost != null) "+boost(${boost.mode})" else ""} all=${eff.allowed.all} " +
            "allow=${eff.allowed.pkgs.size} gray=${eff.gray} sound=$soundNote wa=${eff.wa} inbound=${if (eff.inbound) "allowed" else "BLOCKED"} hid=${res.hid} unhid=${res.unhid} failed=${res.failed} " +
            "lock=${eff.lockdown.launcher}/touch${if (eff.lockdown.touch) "On" else "Off"}/shade${if (eff.lockdown.shade) "On" else "Off"}/kiosk${if (eff.lockdown.kiosk) "On" else "Off"} " +
            "ours=${Store.hidden(app).size} next=${change?.first?.let { Date(it) }} alarm=${Date(alarmAt)}$warnNote")
    }

    private fun arm(c: Context, at: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            c, 1, Intent(c, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val real = System.currentTimeMillis() + Clock.untilReal(at)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, real, pi)
        else { Log.log(c, "exact alarms not permitted, using inexact"); am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, real, pi) }
    }
}

object Notifier {
    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    fun channels(c: Context) {
        nm(c).createNotificationChannel(NotificationChannel(Controller.CH_STATUS, "Phone Guard status", NotificationManager.IMPORTANCE_LOW))
        nm(c).createNotificationChannel(NotificationChannel(Controller.CH_WARN, "Phone Guard warnings", NotificationManager.IMPORTANCE_HIGH))
    }

    fun build(c: Context, text: String): android.app.Notification {
        channels(c)
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return android.app.Notification.Builder(c, Controller.CH_STATUS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Phone Guard")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    fun status(c: Context, text: String) {
        try { nm(c).notify(Controller.NOTE_STATUS, build(c, text)) } catch (_: Exception) { }
    }

    fun warn(c: Context, title: String, text: String) {
        channels(c)
        val n = android.app.Notification.Builder(c, Controller.CH_WARN)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text).setAutoCancel(true).build()
        try { nm(c).notify(Controller.NOTE_WARN, n) } catch (_: Exception) { }
    }
}
