package io.github.durkimat.phoneguard.companion

import android.content.Context

/** The pending enrolment bundle, in device-protected storage (usable before the PIN is entered). Consumed when pairing starts. */
object EnrolStore {
    private fun sp(c: Context) =
        c.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("pg-companion", Context.MODE_PRIVATE)

    fun save(c: Context, b: EnrolBundle) { sp(c).edit().putString("url", b.workerUrl).putString("token", b.token).putString("pin", b.pin).apply() }

    fun pending(c: Context): EnrolBundle? {
        val s = sp(c)
        val u = s.getString("url", null) ?: return null
        return EnrolBundle(u, s.getString("token", "") ?: "", s.getString("pin", "") ?: "")
    }

    fun clear(c: Context) { sp(c).edit().clear().apply() }
}
