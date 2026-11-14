package io.github.durkimat.phoneguard.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeTest {
    private fun t(s: String) = Mime.text(s.replace("\n", "\r\n").toByteArray(Charsets.ISO_8859_1))

    @Test fun plainMessage() {
        assertEquals("Hello there\n\nSee you", t("From: a@b.c\nSubject: x\n\nHello there\n\nSee you\n"))
    }

    @Test fun encodedWordsAndAddresses() {
        assertEquals("Café menu", Mime.decodeWords("=?UTF-8?B?Q2Fmw6kgbWVudQ==?="))
        assertEquals("Café menu", Mime.decodeWords("=?utf-8?Q?Caf=C3=A9_menu?="))
        assertEquals("ab", Mime.decodeWords("=?UTF-8?Q?a?= =?UTF-8?Q?b?="))
        assertEquals("Mum" to "mum@x.org", Mime.address("\"Mum\" <mum@x.org>"))
        assertEquals("a@b.c" to "a@b.c", Mime.address("a@b.c"))
        assertEquals("Dad" to "dad@x.org", Mime.address("=?UTF-8?Q?Dad?= <dad@x.org>"))
    }

    @Test fun quotedPrintableAndCharsets() {
        val raw = "Content-Type: text/plain; charset=iso-8859-1\nContent-Transfer-Encoding: quoted-printable\n\nCaf=E9 =\nbar\n"
        assertEquals("Café bar", t(raw))
        val b64 = "Content-Type: text/plain; charset=utf-8\nContent-Transfer-Encoding: base64\n\n" + java.util.Base64.getEncoder().encodeToString("Grüße".toByteArray()) + "\n"
        assertEquals("Grüße", t(b64))
    }

    @Test fun alternativeWithAttachmentPrefersPlainAndCountsAttachments() {
        val raw = """Content-Type: multipart/mixed; boundary="B1"

--B1
Content-Type: multipart/alternative; boundary="B2"

--B2
Content-Type: text/plain; charset=utf-8

Plain words
--B2
Content-Type: text/html; charset=utf-8

<p>HTML <b>words</b></p>
--B2--
--B1
Content-Type: application/pdf; name="a.pdf"
Content-Disposition: attachment; filename="a.pdf"
Content-Transfer-Encoding: base64

JVBERi0=
--B1--
"""
        assertEquals("Plain words\n\n[1 attachment not shown]", t(raw))
    }

    @Test fun htmlOnlyBecomesText() {
        val raw = "Content-Type: text/html; charset=utf-8\n\n<html><head><style>p{}</style></head><body><p>Hi&nbsp;Sam</p><p>Tea &amp; cake</p><ul><li>one</li><li>two</li></ul></body></html>\n"
        val out = t(raw)
        assertTrue(out, out.startsWith("Hi Sam"))
        assertTrue(out, out.contains("Tea & cake") && out.contains("- one") && out.contains("- two"))
    }

    @Test fun dates() {
        assertEquals(1794574991000L, Mime.date("13-Nov-2026 13:03:11 +0000"))
        assertEquals(1794574991000L, Mime.date("Fri, 13 Nov 2026 13:03:11 +0000"))
        assertEquals(1794574991000L, Mime.date("Fri, 13 Nov 2026 13:03:11 +0000 (UTC)"))
        assertEquals(0L, Mime.date("nonsense"))
    }

    @Test fun unfoldsHeaders() {
        val e = Mime.entity("Subject: one\n two\nFrom: a@b\n\nbody")
        assertEquals("one two", e.headers["subject"])
    }

    @Test fun encodesNonAsciiHeaders() {
        assertEquals("Plain", Mime.encodeHeader("Plain"))
        assertEquals("Café", Mime.decodeWords(Mime.encodeHeader("Café")))
    }
}
