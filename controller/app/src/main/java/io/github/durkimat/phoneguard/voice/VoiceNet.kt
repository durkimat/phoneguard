package io.github.durkimat.phoneguard.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object VoiceNet {
    /** Any network with internet (Wi-Fi or mobile data: the recordings are small). */
    fun online(c: Context): Boolean = try {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { false }
}
