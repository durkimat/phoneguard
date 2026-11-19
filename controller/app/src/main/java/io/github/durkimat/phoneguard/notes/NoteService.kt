package io.github.durkimat.phoneguard.notes

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.durkimat.phoneguard.Log

/**
 * Collects the phone's notifications for the Nokia screens and clears the ones that are switched off for this mode or hidden for the
 * app. It reads the title and text only to show them on the phone's own screen; nothing is sent anywhere.
 */
class NoteService : NotificationListenerService() {
    override fun onListenerConnected() { refresh(); applyWaTone() }
    override fun onNotificationPosted(sbn: StatusBarNotification) { detect(sbn); if (sbn.packageName in WA) applyWaTone(); handle(sbn) }

    /**
     * The pop-up detector: every notification that is posted is logged (who, which channel, how loud: no message text), so a notice that
     * would interrupt the screen shows up in the log instead of waiting for someone to see it. System packages also log their title,
     * because that is how a new system notice is recognised.
     */
    private fun detect(sbn: StatusBarNotification) {
        try {
            val n = sbn.notification
            val imp = currentRanking.let { r -> val rk = android.service.notification.NotificationListenerService.Ranking(); if (r.getRanking(sbn.key, rk)) rk.importance else -1 }
            val sys = sbn.packageName == "android" || sbn.packageName.startsWith("com.android.") || sbn.packageName == "com.google.android.permissioncontroller"
            val title = if (sys) " title=" + (n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(60)) else ""
            Log.log(this, "notice: ${sbn.packageName} id=${sbn.id} channel=${n.channelId} cat=${n.category} importance=$imp$title")
        } catch (e: Exception) { Log.log(this, "notice detector: ${e.javaClass.simpleName}") }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { Notes.remove(sbn.key) }

    private fun refresh() {
        try {
            val list = activeNotifications ?: return
            Notes.clear()
            list.forEach { handle(it) }
        } catch (e: Exception) { Log.log(this, "notes: ${e.javaClass.simpleName}") }
    }

    private fun handle(sbn: StatusBarNotification) {
        val n = sbn.notification
        // never touched: ongoing things (a call, a recording, a running service), alarms, calls, progress, and group headings
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.category in setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_SERVICE)) return
        if (!Notes.enabled || Notes.hidden(sbn.packageName)) { try { cancelNotification(sbn.key) } catch (_: Exception) { }; Notes.remove(sbn.key); return }
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
        Notes.put(NoteItem(sbn.key, sbn.packageName, label, title, text, sbn.postTime, n.contentIntent))
    }

    private val waDone = HashSet<String>()

    /**
     * The child's WhatsApp tune: WhatsApp keeps its own sound on its notification channels, so each channel's sound is set to the picked file
     * (and back to the default notification sound for "stock"). The system may refuse a listener this (it is logged, "tones: ..."); then
     * WhatsApp keeps its own sound.
     */
    fun applyWaTone() {
        val c = this
        val pick = io.github.durkimat.phoneguard.alarm.ToneStore.pick(c, "wa")
        val uri = if (pick == "stock") android.provider.Settings.System.DEFAULT_NOTIFICATION_URI else io.github.durkimat.phoneguard.alarm.ToneStore.uriFor(c, "wa", pick) ?: return
        for (pkg in WA) {
            val chans = try { getNotificationChannels(pkg, android.os.Process.myUserHandle()) } catch (e: Exception) { continue }
            for (ch in chans) {
                if (ch.importance < android.app.NotificationManager.IMPORTANCE_DEFAULT || ch.sound == null) continue // silent channels stay silent
                val tag = "$pkg/${ch.id}/$pick"
                if (tag in waDone || ch.sound == uri) { waDone.add(tag); continue }
                try {
                    ch.setSound(uri, ch.audioAttributes)
                    updateNotificationChannel(pkg, android.os.Process.myUserHandle(), ch)
                    waDone.add(tag); Log.log(c, "tones: WhatsApp channel ${ch.id} set to $pick")
                } catch (e: Exception) { waDone.add(tag); Log.log(c, "tones: WhatsApp channel ${ch.id} refused ${e.javaClass.simpleName}") }
            }
        }
    }

    companion object {
        private val WA = listOf("com.whatsapp", "com.whatsapp.w4b")
        /** Clear one notification from the phone (and so from our list). */
        @Volatile var instance: NoteService? = null
    }

    override fun onCreate() { super.onCreate(); instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }

    /** Settings changed (mode, hidden apps): run every current notification through the rules again. */
    fun reapply() { refresh() }

    fun clearKey(key: String) { try { cancelNotification(key) } catch (_: Exception) { }; Notes.remove(key) }
    /** The messaging app's own notifications are cleared when a conversation is opened here (our list hides them, but the phone's shade still shows them). */
    fun clearMessaging() { try { activeNotifications.filter { it.packageName == "com.android.messaging" && it.isClearable }.forEach { cancelNotification(it.key) } } catch (_: Exception) { } }
    fun clearEverything() { try { cancelAllNotifications() } catch (_: Exception) { }; Notes.clear() }
}
