package io.github.durkimat.phoneguard.incall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.net.Uri
import io.github.durkimat.phoneguard.CallBlocker
import io.github.durkimat.phoneguard.Clock
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.launcher.CallInfo
import io.github.durkimat.phoneguard.launcher.CallPhase

/**
 * Our phone's call handling, once the app holds the Dialer role. It controls the call; [CallActivity] draws it.
 * One call at a time: a second incoming call while one is going is rejected (no call waiting, no conference).
 */
class CallService : InCallService() {
    private val calls = ArrayList<Call>()
    private var answeredAt = HashMap<Call, Long>()
    private var wasAudioRoute = CallAudioState.ROUTE_EARPIECE
    private var muted = false
    private var speaker = false

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (state == Call.STATE_ACTIVE && !answeredAt.containsKey(call)) answeredAt[call] = Clock.now()
            publish()
        }
    }

    override fun onCreate() { super.onCreate(); instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }

    override fun onCallAdded(call: Call) {
        val ringing = call.state == Call.STATE_RINGING
        if (ringing && CallBlocker.blocking(this)) {
            call.reject(false, null)
            Log.log(this, "incall: incoming call rejected by the mode")
            return
        }
        if (ringing && calls.isNotEmpty()) { call.reject(false, null); Log.log(this, "incall: second incoming call rejected (busy)"); return }
        calls.add(call)
        call.registerCallback(callback)
        if (call.state == Call.STATE_ACTIVE) answeredAt[call] = Clock.now()
        publish()
        show(ringing)
    }

    override fun onCallRemoved(call: Call) {
        val start = answeredAt.remove(call)
        call.unregisterCallback(callback)
        val primary = calls.firstOrNull() === call
        calls.remove(call)
        cancelNotification()
        if (primary || calls.isEmpty()) {
            val info = lastInfo
            if (start != null && info != null) CallState.set(info.copy(phase = CallPhase.ENDED, seconds = ((Clock.now() - start) / 1000).toInt()))
            else CallState.set(null) // never answered (missed, rejected, cancelled): nothing to show
        }
        lastInfo = null
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        muted = audioState.isMuted
        speaker = audioState.route == CallAudioState.ROUTE_SPEAKER
        publish()
    }

    private var lastInfo: CallInfo? = null

    private fun publish() {
        val call = calls.firstOrNull() ?: return
        val number = call.details.handle?.schemeSpecificPart ?: ""
        val phase = when (call.state) {
            Call.STATE_RINGING -> CallPhase.RINGING
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_PULLING_CALL, Call.STATE_NEW -> CallPhase.DIALING
            Call.STATE_DISCONNECTED -> CallPhase.ENDED
            else -> CallPhase.ACTIVE
        }
        val info = CallInfo(number, nameFor(number), phase, startedAt = answeredAt[call] ?: 0, muted = muted, speaker = speaker)
        lastInfo = info
        CallState.set(info)
    }

    private fun nameFor(number: String): String? {
        if (number.isBlank()) return null
        return try {
            contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) { null }
    }

    /** Brings the call screen up. A ringing call also gets a full-screen notification, which is how Android lets a call interrupt. */
    private fun show(ringing: Boolean) {
        val intent = Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pm = getSystemService(android.os.PowerManager::class.java)
        val kg = getSystemService(android.app.KeyguardManager::class.java)
        // the banner is only needed to wake a sleeping or locked phone; otherwise it would cover the call screen
        if (ringing && (!pm.isInteractive || kg.isKeyguardLocked)) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Calls", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(false) })
            val pi = PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(NOTE, Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle("Incoming call").setCategory(Notification.CATEGORY_CALL).setFullScreenIntent(pi, true).setOngoing(true).build())
        }
        try { startActivity(intent) } catch (e: Exception) { Log.log(this, "incall: could not start the call screen: ${e.javaClass.simpleName}") }
    }

    private fun cancelNotification() { getSystemService(NotificationManager::class.java).cancel(NOTE) }

    // ---- what the call screen asks for
    private fun primary(): Call? = calls.firstOrNull()
    fun answer() { cancelNotification(); primary()?.answer(VideoProfile.STATE_AUDIO_ONLY) }
    fun reject() { cancelNotification(); primary()?.reject(false, null) }
    fun end() { primary()?.disconnect() }
    fun toggleSpeaker() { setAudioRoute(if (speaker) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER) }
    fun toggleMute() { setMuted(!muted) }
    fun dtmf(tone: Char) { primary()?.let { it.playDtmfTone(tone); android.os.Handler(mainLooper).postDelayed({ it.stopDtmfTone() }, 150) } }
    fun volume(delta: Int) {
        val am = getSystemService(AudioManager::class.java)
        am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
    }

    companion object {
        @Volatile var instance: CallService? = null
        private const val CHANNEL = "calls"
        const val NOTE = 77
    }
}
