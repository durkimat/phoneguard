package io.github.durkimat.phoneguard.companion

import java.net.URLDecoder

/**
 * The enrolment payload v1 the setup tool shows as a QR code (or the parent types in): the Worker's host, a single-use token and, from
 * the QR only, the fingerprint of the server key. [fingerprint] is null on the typed route, where the key is pinned on first contact.
 */
data class EnrolPayload(val host: String, val token: String, val fingerprint: String?) {
    val baseUrl: String get() = "https://$host"

    companion object {
        private val HOST = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
        private val FP = Regex("^[A-Za-z0-9_-]{22}(==)?$")

        /** Parse QR text `phoneguard://enrol?v=1&h=<host>&t=<token>&f=<fp>`; null for an unknown version, missing field or bad value, so nothing is sent. */
        fun parseQr(text: String?): EnrolPayload? {
            val s = text?.trim() ?: return null
            val prefix = "phoneguard://enrol?"
            if (!s.startsWith(prefix, ignoreCase = true)) return null
            val q = HashMap<String, String>()
            for (part in s.substring(prefix.length).split('&')) {
                val i = part.indexOf('=')
                if (i <= 0) return null
                q[part.substring(0, i)] = try { URLDecoder.decode(part.substring(i + 1), "UTF-8") } catch (e: Exception) { return null }
            }
            if (q["v"] != "1") return null
            val host = cleanHost(q["h"] ?: return null) ?: return null
            val token = cleanToken(q["t"] ?: return null) ?: return null
            val f = q["f"] ?: return null
            if (!FP.matches(f)) return null
            return EnrolPayload(host, token, f)
        }

        /** Typed route: the address is the part before `.workers.dev` (a full host is also accepted), the code as shown (ABCDE-FGHJK). */
        fun fromTyped(address: String?, code: String?): EnrolPayload? {
            var a = address?.trim()?.lowercase() ?: return null
            if (a.isEmpty()) return null
            if (a.startsWith("https://")) a = a.removePrefix("https://").trimEnd('/')
            if (!a.endsWith(".workers.dev")) a += ".workers.dev"
            val host = cleanHost(a) ?: return null
            val token = cleanToken(code ?: return null) ?: return null
            return EnrolPayload(host, token, null)
        }

        /** Host only: no scheme, path, port or credentials; the phone always uses https. */
        private fun cleanHost(h: String): String? {
            val x = h.trim().lowercase()
            return if (x.length <= 120 && HOST.matches(x)) x else null
        }

        private fun cleanToken(t: String): String? {
            val n = CompanionCrypto.normaliseToken(t)
            return if (n.length == 10) n else null
        }
    }
}
