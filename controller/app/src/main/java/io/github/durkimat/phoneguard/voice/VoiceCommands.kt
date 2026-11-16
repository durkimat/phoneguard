package io.github.durkimat.phoneguard.voice

import android.content.Context
import android.content.Intent
import io.github.durkimat.phoneguard.launcher.LauncherActivity

/**
 * Spoken commands (phase 3). Nothing is done here beyond judging the command: a call, a play, a timer or "Where to?" is handed to the launcher, which acts
 * through the same screens and rules as by hand (a call only opens the dial pad with the number filled in and waits for OK).
 */
object VoiceCommands {
    fun run(c: Context, r: VoiceReply.Heard, say: (String, Long) -> Unit) {
        val cmd = VoiceCommand.parse(r.command)
        if (cmd is VoiceCommand.Unknown) { say(cmd.say, 2000); return }
        say("", 0) // the panel goes away; the launcher shows the result
        io.github.durkimat.phoneguard.keys.F2Service.cover(false)
        try {
            c.startActivity(Intent(c, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(LauncherActivity.EXTRA_VOICE, r.command))
        } catch (e: Exception) { say("Couldn't do that", 2000) }
    }
}
