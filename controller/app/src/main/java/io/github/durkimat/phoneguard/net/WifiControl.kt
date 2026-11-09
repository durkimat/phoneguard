package io.github.durkimat.phoneguard.net

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.launcher.WifiNet

/**
 * Wi-Fi from the keypad screens. A device owner may switch Wi-Fi on and off and add, change, join and forget networks through the
 * (deprecated) Wi-Fi manager calls, which ordinary apps may no longer use. Passwords are passed straight to Android and never logged.
 */
@Suppress("DEPRECATION")
object WifiControl {
    private fun wm(c: Context): WifiManager = c.applicationContext.getSystemService(WifiManager::class.java)
    private fun unq(s: String?) = (s ?: "").removeSurrounding("\"")

    fun enabled(c: Context): Boolean = try { wm(c).isWifiEnabled } catch (_: Exception) { false }

    fun setEnabled(c: Context, on: Boolean): Boolean = try { wm(c).setWifiEnabled(on) } catch (e: Exception) { Log.log(c, "wifi switch: ${e.javaClass.simpleName}"); false }

    /** Ask Android for a fresh look at what is nearby (it limits how often; the last results are used meanwhile). */
    fun scan(c: Context) { try { wm(c).startScan() } catch (_: Exception) { } }

    /** Saved networks and nearby ones in one list: the connected one first, then saved and in range, saved out of range, and the rest by signal. */
    fun list(c: Context): List<WifiNet> = try {
        val m = wm(c)
        val saved = (try { m.configuredNetworks } catch (_: Exception) { null } ?: emptyList()).associate { unq(it.SSID) to it.networkId }
        val seen = (try { m.scanResults } catch (_: Exception) { null } ?: emptyList()).filter { it.SSID.isNotBlank() }
            .groupBy { it.SSID }.mapValues { e -> e.value.maxByOrNull { it.level }!! }
        val info = try { m.connectionInfo } catch (_: Exception) { null }
        val curId = if (info != null && info.supplicantState == android.net.wifi.SupplicantState.COMPLETED) info.networkId else -1
        (saved.keys + seen.keys).map { ssid ->
            val r = seen[ssid]
            val id = saved[ssid] ?: -1
            val caps = r?.capabilities.orEmpty()
            WifiNet(ssid, id, id >= 0, r == null || caps.contains("WPA") || caps.contains("WEP") || caps.contains("SAE") || caps.contains("PSK"),
                if (r != null) WifiManager.calculateSignalLevel(r.level, 5) else -1, id >= 0 && id == curId, r != null, caps)
        }.sortedWith(compareBy({ !it.connected }, { !(it.saved && it.inRange) }, { !it.saved }, { -it.level }, { it.ssid.lowercase() }))
    } catch (e: Exception) { Log.log(c, "wifi list: ${e.javaClass.simpleName}"); emptyList() }

    fun connect(c: Context, id: Int): Boolean = try { val m = wm(c); m.enableNetwork(id, true).also { m.reconnect() } } catch (e: Exception) { Log.log(c, "wifi connect: ${e.javaClass.simpleName}"); false }

    fun forget(c: Context, id: Int): Boolean = try { wm(c).removeNetwork(id) } catch (e: Exception) { Log.log(c, "wifi forget: ${e.javaClass.simpleName}"); false }

    /** Join a network (an empty password means an open one), or change the password of a saved one (id >= 0). */
    fun join(c: Context, ssid: String, password: String, caps: String, id: Int): Boolean = try {
        val m = wm(c)
        val cfg = WifiConfiguration()
        cfg.SSID = "\"$ssid\""
        if (password.isEmpty()) cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
        else {
            cfg.preSharedKey = "\"$password\""
            // a network that offers only WPA3 needs SAE; one that offers WPA2 as well (most) is joined as WPA2/WPA3 transition
            if (caps.contains("SAE") && !caps.contains("PSK")) cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SAE) else cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        }
        val netId = if (id >= 0) { cfg.networkId = id; if (m.updateNetwork(cfg) >= 0) id else -1 } else m.addNetwork(cfg)
        if (netId < 0) false else { m.enableNetwork(netId, true); m.reconnect(); true }
    } catch (e: Exception) { Log.log(c, "wifi join: ${e.javaClass.simpleName}"); false }
}
