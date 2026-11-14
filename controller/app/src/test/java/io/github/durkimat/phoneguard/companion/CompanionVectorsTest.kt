package io.github.durkimat.phoneguard.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Checks the phone-side rules against the vectors the Worker side generates (companion-vectors.json, copied from companion-worker/test). */
class CompanionVectorsTest {
    private val v = JSONObject(javaClass.getResource("/companion-vectors.json")!!.readText())
    private val phone = v.getJSONObject("phone").getString("publicKeySpki")
    private val server = v.getJSONObject("server").getString("publicKeySpki")

    @Test fun matchNumber() {
        val p = v.getJSONObject("pairing")
        assertEquals(p.getString("matchNumber"), CompanionCrypto.matchNumber(p.getString("token"), phone))
        assertEquals(p.getString("matchNumber"), CompanionCrypto.matchNumber(" 7k3q-m9xd 2p ", phone)) // typed forms normalise
    }

    @Test fun pin() {
        val pin = v.getJSONObject("bundle").getString("pin")
        assertEquals(pin, CompanionCrypto.serverPin(server))
        assertTrue(CompanionCrypto.pinMatches(server, pin))
        assertFalse(CompanionCrypto.pinMatches(phone, pin)) // another key does not match
        assertFalse(CompanionCrypto.pinMatches("not base64!", pin))
    }

    @Test fun serverReply() {
        val e = v.getJSONObject("serverReply").getJSONObject("envelope")
        val m = CompanionCrypto.serverMessage(e.getInt("v"), e.getString("did"), e.getString("n"), e.getLong("t"), e.getString("p"))
        assertEquals(v.getJSONObject("serverReply").getString("messageToSign"), m)
        assertTrue(CompanionCrypto.verify(server, m, e.getString("s")))
        assertFalse(CompanionCrypto.verify(server, m + "x", e.getString("s")))
        assertFalse(CompanionCrypto.verify(phone, m, e.getString("s"))) // wrong signer
    }

    @Test fun phoneSync() {
        val r = v.getJSONObject("phoneSync").getJSONObject("request")
        val m = CompanionCrypto.phoneMessage(r.getInt("v"), r.getString("did"), r.getLong("ctr"), r.getString("n"), r.getString("p"))
        assertEquals(v.getJSONObject("phoneSync").getString("messageToSign"), m)
        assertTrue(CompanionCrypto.verify(phone, m, r.getString("s")))
        assertFalse(CompanionCrypto.verify(phone, m.replace("|7|", "|8|"), r.getString("s")))
    }

    @Test fun bundle() {
        val j = v.getJSONObject("bundle").getJSONObject("json")
        val b = EnrolBundle.parse(j.toString())!!
        assertEquals("https://pg-example.family.workers.dev", b.workerUrl)
        assertEquals("7K3QM9XD2P", b.token)
        assertNull(EnrolBundle.parse(j.put("u", "http://insecure.example").toString()))
        assertNull(EnrolBundle.parse("{}"))
        assertNull(EnrolBundle.parse(null))
        assertEquals("https://x.example" to "7K3QM9XD2P", EnrolBundle.parseSms("pg1:https://x.example:7K3Q-M9XD-2P"))
        assertNull(EnrolBundle.parseSms("hello"))
    }
}

class SealedSecretTest {
    private val s = JSONObject(javaClass.getResource("/companion-vectors.json")!!.readText()).getJSONObject("sealed")
    private val priv = java.security.KeyFactory.getInstance("EC").generatePrivate(
        java.security.spec.PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(s.getJSONObject("phoneEncryptionKey").getString("privateKeyPkcs8"))))

    @Test fun opensTheVector() {
        assertEquals(s.getString("plaintext"), SealedSecret.open(priv, s.getString("did"), s.getString("name"), s.getJSONObject("sealed")))
    }

    @Test fun refusesWrongContextOrTampering() {
        val sealed = s.getJSONObject("sealed")
        assertNull(SealedSecret.open(priv, "other-device", s.getString("name"), sealed))
        assertNull(SealedSecret.open(priv, s.getString("did"), "mail.password", sealed))
        assertNull(SealedSecret.open(priv, s.getString("did"), s.getString("name"), JSONObject(sealed.toString()).put("alg", "none")))
        val ct = java.util.Base64.getDecoder().decode(sealed.getString("ct")); ct[0] = (ct[0].toInt() xor 1).toByte()
        assertNull(SealedSecret.open(priv, s.getString("did"), s.getString("name"),
            JSONObject(sealed.toString()).put("ct", java.util.Base64.getEncoder().encodeToString(ct))))
    }
}
