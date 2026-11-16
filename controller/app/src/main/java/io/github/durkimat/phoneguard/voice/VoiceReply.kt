package io.github.durkimat.phoneguard.voice

import org.json.JSONObject

/** What the Worker said to a voice request (contract: spec-voice.md section 8), and the plain words to show for each outcome. Pure. */
sealed class VoiceReply {
    /** [command] is the Worker's checked spoken command as JSON (only in command mode): {intent, args, say, confirm}. */
    data class Heard(val text: String, val low: Boolean, val command: String? = null) : VoiceReply()
    object Nothing : VoiceReply()
    data class Refused(val shown: String) : VoiceReply()
    data class Failed(val shown: String) : VoiceReply()

    companion object {
        const val NO_NETWORK = "Connect first"
        const val DIDNT_CATCH = "Didn't catch that"

        fun parse(code: Int, body: String): VoiceReply {
            val j = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            if (code == 200) {
                val t = j.optString("text").trim()
                return if (t.isEmpty()) Nothing else Heard(t, j.optString("quality") == "low", j.optJSONObject("command")?.toString())
            }
            return when (j.optString("error")) {
                "voice_off" -> Refused("Voice is off in this mode")
                "too_long" -> Refused("That was too long")
                "used_up" -> Refused("Voice is used up for today")
                "unavailable" -> Failed("Voice isn't working right now")
                else -> Failed(if (code == 429) "Voice is used up for today" else if (code == 403) "Voice is off in this mode" else "Voice isn't working right now")
            }
        }
    }
}
