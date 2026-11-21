package io.github.durkimat.familychat

import io.github.durkimat.wacore.WaCommands

/**
 * The first-run steps, with no screen in them: welcome, PIN, names, link, admin group, groups. A screen only shows [step] and calls the
 * functions. The admin group is found by its FamilyChat-<child> name (WaCommands.ADMIN_PREFIX) and then bound by its id, never by name afterwards.
 */
class SetupFlow(private val s: Settings) {
    enum class Step { WELCOME, PIN, NAMES, LINK, ADMIN_GROUP, GROUPS, DONE }

    val step: Step get() = when {
        !s.riskAccepted -> Step.WELCOME
        s.pin == null -> Step.PIN
        s.childName.isBlank() || s.parentName.isBlank() -> Step.NAMES
        !s.linked -> Step.LINK
        s.adminGroupId.isBlank() -> Step.ADMIN_GROUP
        !s.setupDone -> Step.GROUPS
        else -> Step.DONE
    }

    /** "Step N of 6" for the screen (1..6). */
    val number: Int get() = minOf(step.ordinal + 1, 6)

    fun acceptRisk() { s.riskAccepted = true }

    fun setPin(pin: String, again: String): Boolean {
        if (pin != again || !PinStore.valid(pin)) return false
        s.pin = PinStore.create(pin); return true
    }

    fun setNames(child: String, parent: String): Boolean {
        val c = child.trim(); val p = parent.trim()
        if (c.isEmpty() || p.isEmpty() || c.length > 30 || p.length > 30) return false
        s.childName = c; s.parentName = p
        return true
    }

    /** The groups (the transport's "id|name" lines) that look like this child's admin group. Several: the parent chooses. */
    fun adminCandidates(groups: String): List<Pair<String, String>> =
        WaCommands.adminCandidates(groups).filter { it.second.equals(adminGroupName(), ignoreCase = true) }

    /** The name the parent should give the group in WhatsApp. */
    fun adminGroupName() = WaCommands.adminGroupName(s.childName)

    fun bindAdmin(id: String): Boolean {
        if (id.isBlank()) return false
        s.adminGroupId = id.removeSuffix("@g.us"); return true
    }

    /** The last step: the chosen groups are saved and the app is handed over. */
    fun finish(chosen: List<Group>) { s.allowedGroups = chosen.filter { it.id != s.adminGroupId }; s.setupDone = true; s.commandsVersion = io.github.durkimat.wacore.WaAcks.COMMANDS_VERSION }
}

/** What the app remembers about itself. [Memory] is the test one; the real one is [PrefsSettings]. */
interface Settings {
    var riskAccepted: Boolean
    var childName: String
    var parentName: String
    var linked: Boolean
    var adminGroupId: String
    var pin: PinStore.Saved?
    var pinFailures: Int
    var pinLockUntil: Long
    var allowedGroups: List<Group>
    var lastCommand: Long
    var setupDone: Boolean
    /** The WaAcks.COMMANDS_VERSION the admin group was last told about (the "Ready" message of a fresh setup, or the upgrade note). */
    var commandsVersion: Int
    /** Tell WhatsApp (so the parent's phone shows it read) when the child opens a conversation. On unless the parent turns it off. */
    var sendReadReceipts: Boolean
    /** Pictures, GIFs and stickers are shown (and downloaded). Off: a plain marker instead. */
    var showMedia: Boolean
    /** The ids of the stickers sent from this device, newest first (at most 24). */
    var recentStickers: List<String>
    /** The colour of the child's own bubbles in a chat (a [Themes] bubble key). */
    fun bubbleColor(chat: String): String
    fun setBubbleColor(chat: String, key: String)
    /** The child may send photos and videos (the "+" button). Off unless the parent turns it on, in the admin menu or with "photos on". */
    var photosAllowed: Boolean
    /** Videos are fetched only on Wi-Fi (or any unmetered network). */
    var wifiOnlyVideos: Boolean
    /** The wallpaper chosen for a chat ("sand", "mint", ... or "photo"), by whoever holds the phone. */
    fun wallpaper(chat: String): String
    fun setWallpaper(chat: String, key: String)
    /** The groups last listed by the account, so the pickers have something to show before the connection answers. */
    var knownGroups: List<Group>
    fun clear()
}
