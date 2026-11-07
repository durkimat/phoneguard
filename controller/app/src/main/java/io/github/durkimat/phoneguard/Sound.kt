package io.github.durkimat.phoneguard

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import io.github.durkimat.phoneguard.core.Engine

/**
 * The sound profile of a mode. Normal leaves the phone alone; vibrate keeps the ringer on vibrate; silent
 * uses Do Not Disturb "total silence": nothing sounds or buzzes, with no exceptions. Whether calls may come
 * in at all is a separate setting (see CallBlocker).
 * Silent needs Do Not Disturb access, which a device owner cannot give itself: one adb setup grant.
 * Without it silent falls back to vibrate. We deliberately do not use DISALLOW_ADJUST_VOLUME, because
 * that mutes the whole device, call audio included; GuardService puts the setting back if it is changed.
 */
object SoundEnforcer {
    private fun sp(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("sound", Context.MODE_PRIVATE)

    fun canSilence(c: Context): Boolean = try { c.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted } catch (_: Exception) { false }

    /** Applies [want] and says what it did, for the log. */
    fun apply(c: Context, want: String): String {
        val am = c.getSystemService(AudioManager::class.java)
        val nm = c.getSystemService(NotificationManager::class.java)
        val sp = sp(c)
        val access = nm.isNotificationPolicyAccessGranted
        val active = sp.getBoolean("active", false)
        try {
            if (want == "silent" || want == "vibrate") {
                if (!active) {
                    sp.edit().putBoolean("active", true).putInt("ringer", am.ringerMode)
                        .putInt("filter", if (access) nm.currentInterruptionFilter else NotificationManager.INTERRUPTION_FILTER_ALL).apply()
                }
                if (want == "silent" && access) {
                    // total silence: nothing rings, buzzes or alerts, and there are no exceptions
                    if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_NONE) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                    return "silent (total silence)"
                }
                if (access && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                if (am.ringerMode != AudioManager.RINGER_MODE_VIBRATE) am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                return if (want == "silent") "vibrate (silent needs Do Not Disturb access)" else "vibrate"
            }
            if (active) { // back to normal: put back what the owner had, not a fixed value
                if (access) nm.setInterruptionFilter(sp.getInt("filter", NotificationManager.INTERRUPTION_FILTER_ALL))
                val ringer = sp.getInt("ringer", AudioManager.RINGER_MODE_NORMAL)
                am.ringerMode = if (ringer == AudioManager.RINGER_MODE_SILENT && !access) AudioManager.RINGER_MODE_VIBRATE else ringer
                sp.edit().putBoolean("active", false).apply()
                return "normal (restored)"
            }
            return "normal"
        } catch (e: Exception) {
            Log.log(c, "sound: $want failed ${e.javaClass.simpleName}")
            return "failed ${e.javaClass.simpleName}"
        }
    }

    /** Called when the ringer or Do Not Disturb changes: if the mode wants a fixed sound, put it back. */
    fun reassert(c: Context) {
        val now = Clock.now()
        val eff = Engine.effective(Store.config(c), Store.boost(c), now)
        if (eff.sound != "normal") {
            val r = apply(c, eff.sound)
            Log.log(c, "sound re-asserted: $r")
        }
    }
}
