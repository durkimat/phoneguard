package io.github.durkimat.familychat

import io.github.durkimat.wacore.WaCommands

/**
 * The first-run steps, with no screen in them: names, linking, then the admin group. A screen only shows [step] and calls the functions.
 * The admin group is found by its ChildApp- name and then bound by its id, never by name afterwards.
 */
class SetupFlow(private val s: Settings) {
    enum class Step { NAMES, LINK, ADMIN_GROUP, PIN, DONE }

    val step: Step get() = when {
        s.childName.isBlank() || s.parentName.isBlank() -> Step.NAMES
        !s.linked -> Step.LINK
        s.adminGroupId.isBlank() -> Step.ADMIN_GROUP
        s.pin == null -> Step.PIN
        else -> Step.DONE
    }

    fun setNames(child: String, parent: String): Boolean {
        val c = child.trim(); val p = parent.trim()
        if (c.isEmpty() || p.isEmpty() || c.length > 30 || p.length > 30) return false
        s.childName = c; s.parentName = p
        return true
    }

    /** The groups (the transport's "id|name" lines) that look like this child's admin group. Several: the parent chooses. */
    fun adminCandidates(groups: String): List<Pair<String, String>> =
        WaCommands.adminCandidates(groups).filter { it.second.equals("ChildApp-${s.childName}", ignoreCase = true) }

    /** The name the parent should give the group in WhatsApp. */
    fun adminGroupName() = "ChildApp-${s.childName}"

    fun bindAdmin(id: String): Boolean {
        if (id.isBlank()) return false
        s.adminGroupId = id.removeSuffix("@g.us"); return true
    }

    fun setPin(pin: String): Boolean {
        if (!PinStore.valid(pin)) return false
        s.pin = PinStore.create(pin); return true
    }
}

/** What the app remembers about itself. [Memory] is the test one; the real one is [PrefsSettings]. */
interface Settings {
    var childName: String
    var parentName: String
    var linked: Boolean
    var adminGroupId: String
    var pin: PinStore.Saved?
}
