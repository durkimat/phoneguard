package io.github.durkimat.familychat

import android.content.Context

/** [Settings] in private, device-protected preferences (the connection must be able to start before the first unlock). */
class PrefsSettings(c: Context) : Settings {
    private val p = c.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("familychat", Context.MODE_PRIVATE)

    override var childName: String get() = p.getString("child", "") ?: ""; set(v) = p.edit().putString("child", v).apply()
    override var parentName: String get() = p.getString("parent", "") ?: ""; set(v) = p.edit().putString("parent", v).apply()
    override var linked: Boolean get() = p.getBoolean("linked", false); set(v) = p.edit().putBoolean("linked", v).apply()
    override var adminGroupId: String get() = p.getString("admin", "") ?: ""; set(v) = p.edit().putString("admin", v).apply()
    override var pin: PinStore.Saved?
        get() = p.getString("pin_hash", null)?.let { PinStore.Saved(p.getString("pin_salt", "") ?: "", it, p.getInt("pin_iter", 0)) }
        set(v) = p.edit().apply { if (v == null) { remove("pin_hash"); remove("pin_salt"); remove("pin_iter") } else { putString("pin_hash", v.hash); putString("pin_salt", v.salt); putInt("pin_iter", v.iterations) } }.apply()
}
