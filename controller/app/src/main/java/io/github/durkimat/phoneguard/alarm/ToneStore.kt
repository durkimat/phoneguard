package io.github.durkimat.phoneguard.alarm

import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.RingtoneManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import io.github.durkimat.phoneguard.Log
import java.io.ByteArrayOutputStream

/**
 * The tune the child picked for calls, texts and WhatsApp ("stock" = the phone's own, nothing changed). A pick is put in place as the system's
 * default sound, written as a small WAV in the Ringtones/Notifications folder, so the system plays and loops it itself and the ringer
 * mode (vibrate, silent) and Do Not Disturb apply exactly as they do to the stock sound. The stock sound is remembered and put back.
 */
object ToneStore {
    const val STOCK = "stock"
    const val RING = "ring"; const val SMS = "sms"; const val WA = "wa"
    private fun sp(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("tones", Context.MODE_PRIVATE)

    fun pick(c: Context, slot: String): String = (sp(c).getString("pick_$slot", STOCK) ?: STOCK).let { if (it == STOCK || Tones.all.any { t -> t.id == it }) it else STOCK }

    /** WAV bytes of a tune: 16 kHz mono 16-bit, with [tailMs] of silence so a looping ringtone breathes between rings. */
    fun wav(t: Tones.Tone, tailMs: Int): ByteArray {
        val pcm = Tones.render(t)
        val n = pcm.size + 16 * tailMs
        val out = ByteArrayOutputStream(44 + n * 2)
        fun i32(v: Int) { out.write(v and 255); out.write(v shr 8 and 255); out.write(v shr 16 and 255); out.write(v shr 24 and 255) }
        fun i16(v: Int) { out.write(v and 255); out.write(v shr 8 and 255) }
        out.write("RIFF".toByteArray()); i32(36 + n * 2); out.write("WAVEfmt ".toByteArray()); i32(16); i16(1); i16(1); i32(16000); i32(32000); i16(2); i16(16)
        out.write("data".toByteArray()); i32(n * 2)
        for (k in 0 until n) i16(if (k < pcm.size) pcm[k].toInt() else 0)
        return out.toByteArray()
    }

    /** Saves a pick and puts it in place (or puts the stock sound back). Returns true when the system took it. */
    fun set(c: Context, slot: String, id: String): Boolean {
        sp(c).edit().putString("pick_$slot", id).apply()
        return apply(c, slot, id)
    }

    /** Puts every saved pick in place again (after an update, or when the system lost the file). */
    fun reapplyAll(c: Context) { for (s in listOf(RING, SMS)) if (pick(c, s) != STOCK) apply(c, s, pick(c, s)) }

    /** The saved file for [slot] holding tune [id], for a channel sound (WhatsApp); null when the system will not take it. */
    fun uriFor(c: Context, slot: String, id: String): Uri? = try { place(c, slot, wav(Tones.byId(id), 0)) } catch (e: Exception) { Log.log(c, "tones: file for $slot failed ${e.javaClass.simpleName}"); null }

    private fun type(slot: String) = if (slot == RING) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_NOTIFICATION

    private fun apply(c: Context, slot: String, id: String): Boolean {
        if (slot == WA) return false // WhatsApp keeps its own channel sound: see NoteService.applyWaTone
        try {
            val ty = type(slot); val sp = sp(c)
            if (id == STOCK) {
                val saved = sp.getString("stock_$slot", null)
                if (saved != null) { RingtoneManager.setActualDefaultRingtoneUri(c, ty, if (saved == "none") null else Uri.parse(saved)); sp.edit().remove("stock_$slot").apply() }
                return true
            }
            if (sp.getString("stock_$slot", null) == null) sp.edit().putString("stock_$slot", RingtoneManager.getActualDefaultRingtoneUri(c, ty)?.toString() ?: "none").apply()
            val uri = place(c, slot, wav(Tones.byId(id), if (slot == RING) 1200 else 0)) ?: return false
            RingtoneManager.setActualDefaultRingtoneUri(c, ty, uri)
            return true
        } catch (e: Exception) { Log.log(c, "tones: $slot $id failed ${e.javaClass.simpleName}"); return false }
    }

    /** One file per slot, rewritten in place when the pick changes. */
    private fun place(c: Context, slot: String, bytes: ByteArray): Uri? {
        val name = "pg-$slot.wav"
        val coll = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val r = c.contentResolver
        var uri: Uri? = r.query(coll, arrayOf(MediaStore.Audio.Media._ID), "${MediaStore.Audio.Media.DISPLAY_NAME}=?", arrayOf(name), null)?.use {
            if (it.moveToFirst()) android.content.ContentUris.withAppendedId(coll, it.getLong(0)) else null
        }
        if (uri == null) {
            val v = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name); put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav"); put(MediaStore.Audio.Media.TITLE, "Phone Guard tune")
                put(MediaStore.Audio.Media.RELATIVE_PATH, if (slot == RING) Environment.DIRECTORY_RINGTONES else Environment.DIRECTORY_NOTIFICATIONS)
                if (slot == RING) put(MediaStore.Audio.Media.IS_RINGTONE, true) else put(MediaStore.Audio.Media.IS_NOTIFICATION, true)
            }
            uri = r.insert(coll, v)
        }
        uri ?: return null
        r.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: return null
        return uri
    }

    private var preview: AudioTrack? = null

    /** Plays a tune once, at a modest level, so a pick can be heard while choosing (a new preview cuts the last). */
    fun preview(id: String) {
        try {
            preview?.let { it.stop(); it.release() }; preview = null
            if (id == STOCK) return
            val pcm = Tones.render(Tones.byId(id)).let { if (it.size > 16000 * 6) it.copyOf(16000 * 6) else it }
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            t.write(pcm, 0, pcm.size); t.setVolume(0.6f); t.play(); preview = t
        } catch (_: Exception) { }
    }
}
