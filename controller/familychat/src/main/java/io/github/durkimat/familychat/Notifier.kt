package io.github.durkimat.familychat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** New-message notifications, channel "Messages". Tapping one opens that conversation. One notification per group, replaced as more arrive. */
object Notifier {
    const val CHANNEL = "messages"
    private fun id(chat: String) = 1000 + (chat.hashCode() and 0xFFFFF)

    fun message(c: Context, chat: String, groupName: String, sender: String, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(c, ConversationActivity::class.java).putExtra("id", chat.removePrefix("g")).putExtra("name", groupName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(c, id(chat), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val line = if (sender.isNotBlank()) "$sender: $text" else text
        try {
            nm.notify(id(chat), Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_stat_chat).setContentTitle(groupName.ifBlank { "Family Chat" })
                .setContentText(line).setStyle(Notification.BigTextStyle().bigText(line)).setContentIntent(pi).setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE).build())
        } catch (_: SecurityException) { /* notifications refused: the app still works */ }
    }

    fun cancel(c: Context, chat: String) { c.getSystemService(NotificationManager::class.java).cancel(id(chat)) }
    fun cancelAll(c: Context) { c.getSystemService(NotificationManager::class.java).cancelAll() }
}
