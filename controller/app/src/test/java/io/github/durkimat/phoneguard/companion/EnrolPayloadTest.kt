package io.github.durkimat.phoneguard.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnrolPayloadTest {
    private val fp = "AAECAwQFBgcICQoLDA0ODw"

    @Test fun qrParses() {
        val p = EnrolPayload.parseQr("phoneguard://enrol?v=1&h=phoneguard-x7k2.jane.workers.dev&t=ABCDEFGHJK&f=$fp")!!
        assertEquals("https://phoneguard-x7k2.jane.workers.dev", p.baseUrl)
        assertEquals("ABCDEFGHJK", p.token)
        assertEquals(fp, p.fingerprint)
    }

    @Test fun qrRejectsBadInput() {
        val ok = "phoneguard://enrol?v=1&h=a.b.workers.dev&t=ABCDEFGHJK&f=$fp"
        assertEquals("ABCDEFGHJK", EnrolPayload.parseQr(ok)!!.token)
        assertNull(EnrolPayload.parseQr(ok.replace("v=1", "v=2")))
        assertNull(EnrolPayload.parseQr(ok.replace("&f=$fp", "")))
        assertNull(EnrolPayload.parseQr(ok.replace("a.b.workers.dev", "http://evil.example/x")))
        assertNull(EnrolPayload.parseQr(ok.replace("ABCDEFGHJK", "ABC")))
        assertNull(EnrolPayload.parseQr(ok.replace(fp, "short")))
        assertNull(EnrolPayload.parseQr("https://example.com"))
        assertNull(EnrolPayload.parseQr(null))
    }

    @Test fun typedAppendsWorkersDevAndStripsDashes() {
        val p = EnrolPayload.fromTyped("phoneguard-x7k2.jane", "abcde-fghjk")!!
        assertEquals("phoneguard-x7k2.jane.workers.dev", p.host)
        assertEquals("ABCDEFGHJK", p.token)
        assertNull(p.fingerprint)
    }

    @Test fun typedAcceptsFullHostAndRejectsJunk() {
        assertEquals("a.b.workers.dev", EnrolPayload.fromTyped("https://a.b.workers.dev/", "ABCDEFGHJK")!!.host)
        assertNull(EnrolPayload.fromTyped("", "ABCDEFGHJK"))
        assertNull(EnrolPayload.fromTyped("a b", "ABCDEFGHJK"))
        assertNull(EnrolPayload.fromTyped("a.b", "SHORT"))
    }

    @Test fun sharedVectors() {
        val qr = "phoneguard://enrol?v=1&h=pg-example.family.workers.dev&t=7K3QM9XD2P&f=PHHtKl7AMMJHrNskmOPZag"
        val p = EnrolPayload.parseQr(qr)!!
        assertEquals("pg-example.family.workers.dev", p.host)
        assertEquals("7K3QM9XD2P", p.token)
        assertEquals("PHHtKl7AMMJHrNskmOPZag", p.fingerprint)
        val t = EnrolPayload.fromTyped("pg-example.family", "7K3QM-9XD2P")!!
        assertEquals(p.host, t.host); assertEquals(p.token, t.token)
        assertNull(EnrolPayload.parseQr(qr.replace("v=1", "v=2")))
        assertNull(EnrolPayload.parseQr(qr.replace("h=pg-", "h=http://pg-")))
        assertNull(EnrolPayload.parseQr(qr.replace("7K3QM9XD2P", "7K3QM9XD2")))
        assertNull(EnrolPayload.parseQr(qr.replace("&f=PHHtKl7AMMJHrNskmOPZag", "")))
    }
}
