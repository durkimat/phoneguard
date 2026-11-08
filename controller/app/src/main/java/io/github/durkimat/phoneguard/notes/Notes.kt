package io.github.durkimat.phoneguard.notes

import android.app.PendingIntent

/** One notification as the Nokia screens show it. */
data class NoteItem(val key: String, val pkg: String, val app: String, val title: String, val text: String, val time: Long, val intent: PendingIntent?)

/**
 * The phone's notifications, kept by [NoteService] and shown on the Nokia screens (a symbol with a count on the home screen and a
 * Notifications list). Which apps are hidden altogether is decided by [hidden]: a built-in list of noise plus what HA says.
 */
object Notes {
    /** Noise by default: Android's own housekeeping, the permission note, and apps whose messages the Nokia screens already show. */
    val DEFAULT_HIDDEN = setOf(
        "com.android.permissioncontroller", "android", "com.android.systemui", "com.android.messaging", "com.android.dialer",
        "com.android.server.telecom", "com.android.phone", "io.github.durkimat.phoneguard", "com.android.vending",
    )

    @Volatile var extraHidden: Set<String> = emptySet()
    /** False when the mode switches notifications off: new ones are cleared as they arrive and the list stays empty. */
    @Volatile var enabled = true

    fun hidden(pkg: String) = pkg in DEFAULT_HIDDEN || pkg in extraHidden

    private val items = LinkedHashMap<String, NoteItem>()
    private val listeners = ArrayList<() -> Unit>()

    @Synchronized fun all(): List<NoteItem> = items.values.sortedByDescending { it.time }
    @Synchronized fun count(): Int = if (enabled) items.size else 0
    @Synchronized fun put(n: NoteItem) { items[n.key] = n; fire() }
    @Synchronized fun remove(key: String) { if (items.remove(key) != null) fire() }
    @Synchronized fun clear() { items.clear(); fire() }
    @Synchronized fun replaceAll(list: List<NoteItem>) { items.clear(); list.forEach { items[it.key] = it }; fire() }

    fun onChange(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun offChange(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun fire() { val ls = synchronized(listeners) { ArrayList(listeners) }; ls.forEach { try { it() } catch (_: Exception) { } } }
}
