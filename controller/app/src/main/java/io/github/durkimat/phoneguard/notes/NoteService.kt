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
    override fun onListenerConnected() { refresh() }
    override fun onNotificationPosted(sbn: StatusBarNotification) { handle(sbn) }
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

    companion object {
        /** Clear one notification from the phone (and so from our list). */
        @Volatile var instance: NoteService? = null
    }

    override fun onCreate() { super.onCreate(); instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }

    /** Settings changed (mode, hidden apps): run every current notification through the rules again. */
    fun reapply() { refresh() }

    fun clearKey(key: String) { try { cancelNotification(key) } catch (_: Exception) { }; Notes.remove(key) }
    fun clearEverything() { try { cancelAllNotifications() } catch (_: Exception) { }; Notes.clear() }
}
