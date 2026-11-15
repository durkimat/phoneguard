package io.github.durkimat.phoneguard.companion

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The wire rules shared with the Worker (companion/PROTOCOL-DRAFT.md, checked against companion-vectors.json).
 * Pure JVM code: no Android classes, so the unit tests run it against the shared test vectors.
 */
object CompanionCrypto {
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    fun sha256Hex(s: String): String = sha256(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Token as typed or sent: upper case, everything outside A-Z0-9 removed. */
    fun normaliseToken(t: String): String = t.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    /** 4-digit number both screens show: first 4 bytes of SHA-256(token ":" phoneSpkiBase64), unsigned big-endian, last 4 decimal digits. */
    fun matchNumber(token: String, phoneSpkiBase64: String): String {
        val h = sha256("${normaliseToken(token)}:$phoneSpkiBase64".toByteArray(Charsets.UTF_8))
        val n = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
        return (n % 10000).toString().padStart(4, '0')
    }

    /** Pin carried in the enrolment bundle: base64 of the first 16 bytes of SHA-256 of the server's DER SPKI. */
    fun serverPin(serverSpkiBase64: String): String =
        Base64.getEncoder().encodeToString(sha256(Base64.getDecoder().decode(serverSpkiBase64)).copyOf(16))

    /** The key fetched from /api/info is accepted only if its pin equals the bundle's `k`. */
    fun pinMatches(serverSpkiBase64: String, pin: String): Boolean =
        try { MessageDigest.isEqual(serverPin(serverSpkiBase64).toByteArray(), pin.toByteArray()) } catch (e: Exception) { false }

    /** QR fingerprint `f`: base64url (padding optional) of the first 16 bytes of SHA-256 of the server's DER SPKI. */
    fun fingerprintMatches(serverSpkiBase64: String, f: String): Boolean = try {
        val mine = serverPin(serverSpkiBase64).trimEnd('=')
        val theirs = f.replace('-', '+').replace('_', '/').trimEnd('=')
        MessageDigest.isEqual(mine.toByteArray(), theirs.toByteArray())
    } catch (e: Exception) { false }

    /** Message the Worker signs: `v|did|n|t|p`, p exactly as transmitted. */
    fun serverMessage(v: Int, did: String, n: String, t: Long, p: String) = "$v|$did|$n|$t|$p"

    /** Message the phone signs: `v|did|ctr|n|p`. */
    fun phoneMessage(v: Int, did: String, ctr: Long, n: String, p: String) = "$v|$did|$ctr|$n|$p"

    /** ECDSA P-256 / SHA-256, DER signature in base64, public key X.509 SPKI base64. False on any malformed input. */
    fun verify(spkiBase64: String, message: String, sigBase64: String): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(spkiBase64)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update(message.toByteArray(Charsets.UTF_8)); verify(Base64.getDecoder().decode(sigBase64))
        }
    } catch (e: Exception) { false }
}
