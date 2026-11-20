package io.github.durkimat.familychat

import android.content.Context

/** [Settings] in private, device-protected preferences (the connection must be able to start before the first unlock). */
class PrefsSettings(c: Context) : Settings {
    private val p = c.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("familychat", Context.MODE_PRIVATE)

    private fun str(k: String, d: String = "") = p.getString(k, d) ?: d
    override var riskAccepted: Boolean get() = p.getBoolean("risk", false); set(v) = p.edit().putBoolean("risk", v).apply()
    override var childName: String get() = str("child"); set(v) = p.edit().putString("child", v).apply()
    override var parentName: String get() = str("parent"); set(v) = p.edit().putString("parent", v).apply()
    override var linked: Boolean get() = p.getBoolean("linked", false); set(v) = p.edit().putBoolean("linked", v).apply()
    override var adminGroupId: String get() = str("admin"); set(v) = p.edit().putString("admin", v).apply()
    override var pinFailures: Int get() = p.getInt("pin_fail", 0); set(v) = p.edit().putInt("pin_fail", v).apply()
    override var pinLockUntil: Long get() = p.getLong("pin_lock", 0); set(v) = p.edit().putLong("pin_lock", v).apply()
    override var lastCommand: Long get() = p.getLong("last_cmd", 0); set(v) = p.edit().putLong("last_cmd", v).apply()
    override var setupDone: Boolean get() = p.getBoolean("done", false); set(v) = p.edit().putBoolean("done", v).apply()
    override var allowedGroups: List<Group> get() = Group.parse(str("allowed")); set(v) = p.edit().putString("allowed", Group.format(v)).apply()
    override var knownGroups: List<Group> get() = Group.parse(str("known")); set(v) = p.edit().putString("known", Group.format(v)).apply()
    override var pin: PinStore.Saved?
        get() = p.getString("pin_hash", null)?.let { PinStore.Saved(str("pin_salt"), it, p.getInt("pin_iter", 0), p.getInt("pin_len", 4)) }
        set(v) = p.edit().apply { if (v == null) { remove("pin_hash"); remove("pin_salt"); remove("pin_iter"); remove("pin_len") } else { putString("pin_hash", v.hash); putString("pin_salt", v.salt); putInt("pin_iter", v.iterations); putInt("pin_len", v.length) } }.apply()
    override fun clear() { p.edit().clear().apply() }
}
