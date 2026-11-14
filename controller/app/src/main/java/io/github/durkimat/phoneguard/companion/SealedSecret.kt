package io.github.durkimat.phoneguard.companion

import org.json.JSONObject
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A connector secret (Spotify refresh token, mail password) sealed by the parent's browser to the phone's encryption key, relayed by the
 * Worker as ciphertext only (companion PROTOCOL-DRAFT "Sealed credentials"). ECDH P-256 -> HKDF-SHA256 -> AES-256-GCM.
 * Takes a [PrivateKey], so an Android Keystore key (PURPOSE_AGREE_KEY) works as well as a software key in the unit tests.
 */
object SealedSecret {
    const val ALG = "ECDH-P256+HKDF-SHA256+AES-256-GCM"
    private val b64 = Base64.getDecoder()

    /** HKDF (RFC 5869) with SHA-256: extract with the salt, expand to [len] bytes (at most 32 here). */
    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int = 32): ByteArray {
        require(len in 1..32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info); mac.update(1.toByte())
        return mac.doFinal().copyOf(len)
    }

    /** Plaintext of a sealed object, or null if it is for another device/name, tampered with, malformed or an unknown algorithm. */
    fun open(encKey: PrivateKey, did: String, name: String, sealed: JSONObject): String? = try {
        if (sealed.getString("alg") != ALG) null else {
            val epk = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b64.decode(sealed.getString("epk"))))
            val shared = KeyAgreement.getInstance("ECDH").run { init(encKey); doPhase(epk, true); generateSecret() }
            val key = hkdf(shared, b64.decode(sealed.getString("salt")), "phoneguard-sealed-v1|$did|$name".toByteArray(Charsets.UTF_8))
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b64.decode(sealed.getString("iv"))))
            c.updateAAD("$did|$name".toByteArray(Charsets.UTF_8))
            String(c.doFinal(b64.decode(sealed.getString("ct"))), Charsets.UTF_8) // ciphertext || 16-byte tag
        }
    } catch (e: Exception) { null }
}
