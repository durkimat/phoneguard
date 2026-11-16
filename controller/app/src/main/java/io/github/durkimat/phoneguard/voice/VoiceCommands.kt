package io.github.durkimat.phoneguard.voice

import android.content.Context
import org.json.JSONObject

/** Spoken commands (phase 3). For now only shows what was understood; the actions (navigate, play, call, timer) come next. */
object VoiceCommands {
    fun run(c: Context, r: VoiceReply.Heard, say: (String, Long) -> Unit) {
        val cmd = try { r.command?.let { JSONObject(it) } } catch (_: Exception) { null }
        val intent = cmd?.optString("intent").orEmpty()
        if (cmd == null || intent == "unknown" || intent.isEmpty()) { say("Sorry, I didn't catch that", 2000); return }
        say(cmd.optString("say").ifEmpty { r.text }, 2500)
    }
}
