package io.github.durkimat.phoneguard.companion

/**
 * What the phone needs from a backend. The Cloudflare Worker is the first implementation; a Home Assistant one can follow behind the same
 * interface (the phone code never sees which one it talks to).
 */
interface CompanionBackend {
    /** Start pairing with the one-time token; the server key is checked against the bundle pin. */
    fun pair(token: String, serverPinOrKey: String): PairingHandle
    /** Poll a pending pairing: waiting for the parent's confirmation, confirmed (with the signed reply), or refused/expired. */
    fun pollPair(id: String): PairResult
    /** Send the signed state, get back the signed reply (config, version). */
    fun sync(state: String): SyncReply
    /** The current profile document (JSON text), as last signed by the server. */
    fun fetchConfig(): String
}

/** [match] is the 4-digit number to show on the phone; the parent compares it on their page. */
data class PairingHandle(val id: String, val match: String)

sealed class PairResult {
    object Waiting : PairResult()
    data class Confirmed(val deviceId: String, val payload: String) : PairResult()
    data class Refused(val reason: String) : PairResult()
}

/** A server reply whose signature has already been verified; [payload] is the exact JSON text that was signed. */
data class SyncReply(val deviceId: String, val nonce: String, val timeMillis: Long, val payload: String)
