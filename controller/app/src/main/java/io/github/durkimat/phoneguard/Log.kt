package io.github.durkimat.phoneguard

import android.content.Context
import android.util.Log as AndroidLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Plain log to logcat and a small file the rig and the status screen read. Never holds secrets. */
object Log {
    private const val FILE = "controller.log"
    private const val MAX = 200_000L
    @Volatile var listener: (() -> Unit)? = null

    /** External app dir when storage is unlocked (the rig reads it); otherwise device-protected internal. */
    private fun file(c: Context): File {
        val unlocked = c.getSystemService(android.os.UserManager::class.java).isUserUnlocked
        val ext = if (unlocked) c.getExternalFilesDir(null) else null
        return File(ext ?: c.createDeviceProtectedStorageContext().filesDir, FILE)
    }

    @Synchronized
    fun log(c: Context, msg: String) {
        AndroidLog.i("PhoneGuard", msg)
        try {
            val f = file(c)
            if (f.length() > MAX) f.writeText(f.readText().takeLast((MAX / 2).toInt()))
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.UK).format(Date())
            f.appendText("$ts  $msg\n")
        } catch (_: Exception) {
        }
        listener?.invoke()
    }

    fun tail(c: Context, n: Int): String =
        try { file(c).readLines().takeLast(n).joinToString("\n") } catch (_: Exception) { "" }

    fun clear(c: Context) { try { file(c).writeText("") } catch (_: Exception) { } }
}
