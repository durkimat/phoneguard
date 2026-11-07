package io.github.durkimat.phoneguard

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/** This phone's signing key. It lives in the Android Keystore and never leaves it. */
object Keys {
    private const val ALIAS = "pg_device_key"
    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun ensure() {
        if (ks().containsAlias(ALIAS)) return
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        gen.generateKeyPair()
    }

    fun publicB64(): String {
        ensure()
        return Base64.encodeToString(ks().getCertificate(ALIAS).publicKey.encoded, Base64.NO_WRAP)
    }

    fun sign(data: ByteArray): String {
        ensure()
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(ks().getKey(ALIAS, null) as PrivateKey)
        s.update(data)
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP)
    }

    fun delete() { try { ks().deleteEntry(ALIAS) } catch (_: Exception) { } }
}

/** The message format shared with the Home Assistant integration (protocol.py). */
object Wire {
    const val V = 1

    fun requestBytes(did: String, ctr: Long, n: String, p: String) = "PG1\n$did\n$ctr\n$n\n$p".toByteArray()
    fun replyBytes(did: String, n: String, t: Long, p: String) = "PG1R\n$did\n$n\n$t\n$p".toByteArray()

    fun nonce(): String {
        val b = ByteArray(12); SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }

    /** Four digits both sides show, so the parent can confirm they paired the right phone. */
    fun sas(devicePk: String, haPk: String, code: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest("$devicePk|$haPk|$code".toByteArray())
        val first = h.take(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
        return "%04d".format(first % 10000)
    }

    fun verifyHa(haPkB64: String, data: ByteArray, sigB64: String): Boolean = try {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(haPkB64, Base64.NO_WRAP)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(pub); update(data); verify(Base64.decode(sigB64, Base64.NO_WRAP))
        }
    } catch (_: Exception) { false }
}
