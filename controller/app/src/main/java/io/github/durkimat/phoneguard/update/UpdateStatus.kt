package io.github.durkimat.phoneguard.update

import org.json.JSONObject

/** Where an update of Phone Guard itself has got to. */
enum class Phase { WAITING, DOWNLOADING, INSTALLING, FAILED, DONE }

/**
 * The state of the latest update job, as the phone shows it (About phone), says it (a short notice) and reports it to the parent.
 * [job] is the companion's id for it, [version] the version asked for (empty for a test build), [detail] why it waits or failed.
 */
data class UpdateStatus(val job: String, val version: String, val phase: Phase, val detail: String = "", val percent: Int = -1, val at: Long = 0) {
    fun toJson(): JSONObject = JSONObject().put("job", job).put("version", version).put("phase", phase.name.lowercase())
        .put("detail", detail).put("percent", percent).put("at", at)

    /** Changes only when there is something new to tell the parent: not for each percent, nor each point of battery. */
    val reportKey: String get() = "$job|$phase|" + detail.replace(Regex("[0-9]+"), "#")

    /** One or two plain sentences for the phone's own screen. */
    val words: String get() {
        val name = if (version.isEmpty()) "the update" else version
        return when (phase) {
            Phase.WAITING -> "Waiting to get $name: " + waitingWords(detail)
            Phase.DOWNLOADING -> "Downloading $name" + if (percent >= 0) ": $percent%" else ""
            Phase.INSTALLING -> "Installing $name. The phone may pause."
            Phase.FAILED -> "Could not update to $name: " + plainError(detail).ifBlank { "no reason given" }
            Phase.DONE -> "Done, " + detail.ifBlank { "now on $name" }
        }
    }

    companion object {
        fun fromJson(o: JSONObject): UpdateStatus? = try {
            UpdateStatus(o.getString("job"), o.optString("version"), Phase.valueOf(o.getString("phase").uppercase()), o.optString("detail"), o.optInt("percent", -1), o.optLong("at"))
        } catch (_: Exception) { null }

        /** A network error as Java words it, said to a person; anything else is passed on, kept short. */
        fun plainError(why: String): String = when {
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in why -> "Android refused it: it is signed with a different key"
            "INSTALL_FAILED_VERSION_DOWNGRADE" in why -> "Android refused it: it is older than the one installed"
            "INSTALL_FAILED_INSUFFICIENT_STORAGE" in why -> "not enough free space"
            "UnknownHostException" in why || "ConnectException" in why -> "could not reach the internet or the server"
            "SocketTimeoutException" in why -> "the connection timed out"
            "SSL" in why -> "a secure connection could not be made"
            else -> why.take(90)
        }

        /** The installer's reasons for waiting, said to a person. */
        fun waitingWords(why: String) = when {
            why == "no Wi-Fi" -> "needs Wi-Fi"
            why.startsWith("battery") -> "$why, needs 25% or charging"
            why == "storage" -> "not enough free space"
            else -> why
        }

        /** The version in the companion's name for the job ("Phone Guard 0.5.1"), or "" when it has none. */
        fun versionIn(jobName: String): String = Regex("""\b\d+\.\d+\.\d+\b""").find(jobName)?.value.orEmpty()
    }
}

/**
 * An update of ourselves that has been handed to Android and not yet seen to happen. The installed app as it was before is kept:
 * only a change in it proves the update went in (this process is replaced, so there is nobody to be told at the time).
 */
data class SelfInstall(val job: String, val version: String, val codeBefore: Long, val updatedBefore: Long, val startedAt: Long) {
    enum class Outcome { INSTALLED, NOT_INSTALLED, STILL_GOING }

    fun outcome(codeNow: Long, updatedNow: Long, now: Long): Outcome = when {
        codeNow != codeBefore || updatedNow != updatedBefore -> Outcome.INSTALLED
        now - startedAt >= GIVE_UP_MS || now < startedAt - GIVE_UP_MS -> Outcome.NOT_INSTALLED
        else -> Outcome.STILL_GOING
    }

    fun toJson(): JSONObject = JSONObject().put("job", job).put("version", version).put("code", codeBefore).put("updated", updatedBefore).put("started", startedAt)

    companion object {
        const val GIVE_UP_MS = 10 * 60_000L
        fun fromJson(o: JSONObject): SelfInstall? = try {
            SelfInstall(o.getString("job"), o.optString("version"), o.getLong("code"), o.getLong("updated"), o.getLong("started"))
        } catch (_: Exception) { null }
    }
}
