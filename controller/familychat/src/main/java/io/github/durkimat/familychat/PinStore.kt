package io.github.durkimat.familychat

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The parent's PIN, kept only on this phone, only as a salted hash. [Saved] is what goes into private preferences. */
object PinStore {
    data class Saved(val salt: String, val hash: String, val iterations: Int, val length: Int = 4)

    private const val ITER = 120_000

    fun create(pin: String, random: SecureRandom = SecureRandom()): Saved {
        require(valid(pin)) { "A PIN is 4 to 8 digits." }
        val salt = ByteArray(16).also(random::nextBytes)
        return Saved(hex(salt), hex(derive(pin, salt, ITER)), ITER, pin.length)
    }

    fun check(pin: String, s: Saved?): Boolean {
        if (s == null || !valid(pin)) return false
        return MessageDigest.isEqual(derive(pin, unhex(s.salt), s.iterations), unhex(s.hash))
    }

    fun valid(pin: String) = pin.length in 4..8 && pin.all { it in '0'..'9' }

    /** Wrong tries slow the next try down: nothing for 4, then 30 s, 1 min, 5 min, 15 min. */
    fun lockSeconds(failures: Int): Int = when { failures < 5 -> 0; failures == 5 -> 30; failures == 6 -> 60; failures == 7 -> 300; else -> 900 }

    private fun derive(pin: String, salt: ByteArray, iterations: Int) =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, 256)).encoded

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** The admin PIN screen's rule: a wrong PIN slows the next try down ([PinStore.lockSeconds]); the count survives restarts. */
class PinGate(private val s: Settings, private val now: () -> Long = System::currentTimeMillis) {
    sealed class Result { object Ok : Result(); data class Wrong(val lockedSeconds: Int) : Result(); data class Locked(val seconds: Int) : Result() }

    fun lockedSeconds(): Int = maxOf(0, ((s.pinLockUntil - now() + 999) / 1000).toInt())

    fun attempt(pin: String): Result {
        val left = lockedSeconds()
        if (left > 0) return Result.Locked(left)
        if (PinStore.check(pin, s.pin)) { s.pinFailures = 0; s.pinLockUntil = 0; return Result.Ok }
        s.pinFailures += 1
        val lock = PinStore.lockSeconds(s.pinFailures)
        s.pinLockUntil = if (lock > 0) now() + lock * 1000L else 0
        return Result.Wrong(lock)
    }
}
