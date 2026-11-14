package io.github.durkimat.phoneguard.companion

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/**
 * The phone's encryption key for sealed credentials: an EC P-256 key in the Android Keystore that can only be used for key agreement
 * (PURPOSE_AGREE_KEY, API 31+). Separate from the signing key, so a sealed value can never be confused with a signature.
 */
object EncKey {
    private const val ALIAS = "pg_enc_key"
    val supported get() = Build.VERSION.SDK_INT >= 31
    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun ensure() {
        if (!supported || ks().containsAlias(ALIAS)) return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
            initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_AGREE_KEY).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).build())
            generateKeyPair()
        }
    }

    /** X.509 SPKI base64 sent as `encPub` at pairing; null where the platform cannot do it (the server then uses the signing key). */
    fun publicB64(): String? {
        if (!supported) return null
        ensure()
        return Base64.encodeToString(ks().getCertificate(ALIAS).publicKey.encoded, Base64.NO_WRAP)
    }

    fun privateKey(): PrivateKey? { if (!supported) return null; ensure(); return ks().getKey(ALIAS, null) as? PrivateKey }

    /** Proves the Keystore key really does ECDH on this phone: both sides of an exchange with a software key must give the same 32 bytes. */
    fun selfTest(): String {
        if (!supported) return "skipped (API < 31)"
        return try {
            val eph = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val mine = ks().getCertificate(ALIAS).publicKey
            ensure()
            val a = javax.crypto.KeyAgreement.getInstance("ECDH").run { init(privateKey()); doPhase(eph.public, true); generateSecret() }
            val b = javax.crypto.KeyAgreement.getInstance("ECDH").run { init(eph.private); doPhase(mine, true); generateSecret() }
            if (a.size == 32 && a.contentEquals(b)) "ok" else "FAILED (secrets differ)"
        } catch (e: Exception) { "FAILED ${e.javaClass.simpleName}: ${e.message}" }
    }

    fun delete() { try { ks().deleteEntry(ALIAS) } catch (_: Exception) { } }
}
