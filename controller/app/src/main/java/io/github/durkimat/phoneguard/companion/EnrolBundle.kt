package io.github.durkimat.phoneguard.companion

import org.json.JSONObject

/**
 * What the setup helper (or an SMS "pg1:<url>:<token>") hands the phone: where the family's Worker is, the one-time token and the pin of
 * the server key. Kept under about 200 bytes so it fits one SMS.
 */
data class EnrolBundle(val workerUrl: String, val token: String, val pin: String) {
    companion object {
        /** Parse and validate; null for anything that is not a version-1 bundle with an https URL, so a bad push is ignored, never half-applied. */
        fun parse(text: String?): EnrolBundle? {
            if (text == null) return null
            return try {
            val j = JSONObject(text)
            val url = j.getString("u").trimEnd('/')
            val token = CompanionCrypto.normaliseToken(j.getString("t"))
            val pin = j.getString("k")
            if (j.getInt("v") != 1 || !url.startsWith("https://") || url.length > 120 || token.length < 8 || token.length > 16 || pin.length != 24) null
            else EnrolBundle(url, token, pin)
        } catch (e: Exception) { null }
        }

        /** SMS form `pg1:<https url>:<token>`; the pin then comes from the pairing handshake's two-sided match instead. */
        fun parseSms(text: String?): Pair<String, String>? {
            val t = text?.trim() ?: return null
            if (!t.startsWith("pg1:")) return null
            val rest = t.removePrefix("pg1:")
            val i = rest.lastIndexOf(':')
            if (i <= 0) return null
            val url = rest.substring(0, i).trimEnd('/'); val token = CompanionCrypto.normaliseToken(rest.substring(i + 1))
            return if (url.startsWith("https://") && token.length in 8..16) url to token else null
        }
    }
}
