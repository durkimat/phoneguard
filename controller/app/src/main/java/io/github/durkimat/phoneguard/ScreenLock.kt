package io.github.durkimat.phoneguard

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import io.github.durkimat.phoneguard.companion.SealedSecret
import io.github.durkimat.phoneguard.mail.Vault
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The phone's screen-lock PIN, set by the parent (from the companion) or by the child (on the phone, without Android Settings). On a Family phone
 * (device owner) both go through the device-policy reset-password token: no lock is needed to start with, and once a lock exists the token has
 * to be confirmed once with the current PIN. The phone keeps a salted hash of the PIN it set (sealed in the key store) only so that the child's
 * "change/remove" can ask for the current PIN; the PIN itself is never kept or logged. A newer parent revision always wins.
 */
object ScreenLock {
    private fun sp(c: Context) = c.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("screenlock", Context.MODE_PRIVATE)
    private fun dpm(c: Context): DevicePolicyManager = Dpm.dpm(c)
    private fun km(c: Context) = c.getSystemService(KeyguardManager::class.java)
    private val pinRe = Regex("^[0-9]{4,8}$")

    /** True when the last config pass could not be applied and should be sent again at the next check-in. */
    @Volatile var retry = false
        private set

    fun valid(pin: String) = pinRe.matches(pin)
    fun isSet(c: Context): Boolean = try { km(c).isDeviceSecure } catch (_: Exception) { false }
    fun parentRev(c: Context): Int = sp(c).getInt("rev", 0)

    /** Creates the reset token the first time (it is active at once while no lock is set); true when the token can be used now. */
    fun tokenReady(c: Context): Boolean {
        if (!Dpm.isOwner(c)) return false
        return try {
            val admin = Dpm.admin(c); val p = sp(c)
            if (p.getString("token", null) == null) {
                val t = ByteArray(32).also { SecureRandom().nextBytes(it) }
                if (!dpm(c).setResetPasswordToken(admin, t)) return false
                p.edit().putString("token", Vault.seal(java.util.Base64.getEncoder().encodeToString(t))).apply()
            }
            dpm(c).isResetPasswordTokenActive(admin)
        } catch (e: Exception) { Log.log(c, "screen lock: token problem: ${e.javaClass.simpleName}"); false }
    }

    private fun token(c: Context): ByteArray? = sp(c).getString("token", null)?.let { Vault.open(it) }?.let { java.util.Base64.getDecoder().decode(it) }

    /** Intent that asks for the current PIN once, which activates the token when a lock already exists. Null when nothing to confirm. */
    fun confirmIntent(c: Context): android.content.Intent? =
        km(c).createConfirmDeviceCredentialIntent("Phone Guard", "Enter your PIN so your parent can help")

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, 20_000, 256)).encoded

    private fun remember(c: Context, pin: String?, by: String?) {
        val e = sp(c).edit()
        if (pin == null) e.remove("hash") else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val b64 = java.util.Base64.getEncoder()
            e.putString("hash", Vault.seal(b64.encodeToString(salt) + ":" + b64.encodeToString(hash(pin, salt))))
        }
        if (by == null) e.remove("by") else e.putString("by", by)
        e.apply()
    }

    /** True when [pin] is the one this phone set (so the child may change or remove it). False when unknown. */
    fun matches(c: Context, pin: String): Boolean {
        val stored = sp(c).getString("hash", null)?.let { Vault.open(it) } ?: return false
        val (s, h) = stored.split(':').map { java.util.Base64.getDecoder().decode(it) }
        return java.security.MessageDigest.isEqual(hash(pin, s), h)
    }

    /** Whether the phone knows its PIN (set through Phone Guard), so "current PIN" can be checked. */
    fun canVerify(c: Context) = sp(c).getString("hash", null) != null

    /** Sets (pin) or clears (null) the lock through the device owner. Returns "" on success, else a short plain reason. */
    private fun write(c: Context, pin: String?): String {
        if (!Dpm.isOwner(c)) return "Needs a Family phone"
        if (!tokenReady(c)) return if (isSet(c)) "Confirm your PIN once first" else "Not ready yet"
        val t = token(c) ?: return "Not ready yet"
        return try {
            val d = dpm(c); val a = Dpm.admin(c)
            try { d.setPasswordQuality(a, DevicePolicyManager.PASSWORD_QUALITY_NUMERIC); d.setPasswordMinimumLength(a, 4) } catch (_: Exception) { }
            if (d.resetPasswordWithToken(a, pin, t, 0)) "" else "The phone refused the PIN"
        } catch (e: Exception) { Log.log(c, "screen lock: reset failed: ${e.javaClass.simpleName}"); "Could not change the PIN" }
    }

    /** The child sets or changes the PIN (current is needed when one is set). */
    fun childSet(c: Context, current: String, pin: String): String {
        if (!valid(pin)) return "Use 4 to 8 digits"
        if (isSet(c)) { if (!canVerify(c)) return "Ask your parent"; if (!matches(c, current)) return "Current PIN is wrong" }
        val r = write(c, pin)
        if (r.isEmpty()) { remember(c, pin, "child"); sp(c).edit().remove("error").apply(); Log.log(c, "screen lock: set on the phone") }
        return r
    }

    /** The child removes the PIN (the current one is needed). */
    fun childRemove(c: Context, current: String): String {
        if (isSet(c)) { if (!canVerify(c)) return "Ask your parent"; if (!matches(c, current)) return "Current PIN is wrong" }
        val r = write(c, null)
        if (r.isEmpty()) { remember(c, null, null); Log.log(c, "screen lock: removed on the phone") }
        return r
    }

    /** `config.lock` (+ `secrets["lock.pin"]`): applied once per revision, and a newer revision always overrides the child's PIN. */
    fun applyFromConfig(c: Context, lock: JSONObject?, secrets: JSONObject, did: String, key: java.security.PrivateKey?) {
        retry = false
        if (lock == null) return
        val rev = lock.optInt("rev", 0)
        val p = sp(c)
        if (rev <= p.getInt("rev", 0)) return
        val r: String = when (lock.optString("op")) {
            "set" -> {
                val sealed = secrets.optJSONObject(lock.optString("secret", "lock.pin"))
                val pin = if (sealed == null || key == null) null else SealedSecret.open(key, did, "lock.pin", sealed)
                if (pin == null || !valid(pin)) "The PIN from your parent could not be read" else write(c, pin).also { if (it.isEmpty()) remember(c, pin, "parent") }
            }
            "clear" -> write(c, null).also { if (it.isEmpty()) remember(c, null, null) }
            else -> "Unknown request"
        }
        if (r.isEmpty()) {
            p.edit().putInt("rev", rev).putBoolean("parentChanged", true).remove("error").apply()
            Log.log(c, "screen lock: parent ${lock.optString("op")} applied (rev $rev)")
        } else {
            p.edit().putString("error", r).apply()
            retry = r == "Confirm your PIN once first" || r == "Not ready yet" // send the config again until it can be applied
            Log.log(c, "screen lock: parent request not applied: $r")
        }
    }

    /** "Your parent changed your PIN": true once after a parent change, until the child has seen it. */
    fun takeParentNote(c: Context): Boolean { val p = sp(c); val v = p.getBoolean("parentChanged", false); if (v) p.edit().putBoolean("parentChanged", false).apply(); return v }

    /** For the sync state. */
    fun state(c: Context): JSONObject {
        val p = sp(c)
        val o = JSONObject().put("supported", Dpm.isOwner(c) && tokenReady(c)).put("set", isSet(c)).put("rev", p.getInt("rev", 0))
        o.put("by", if (isSet(c)) p.getString("by", null) ?: JSONObject.NULL else JSONObject.NULL)
        p.getString("error", null)?.let { o.put("error", it) }
        return o
    }
}
