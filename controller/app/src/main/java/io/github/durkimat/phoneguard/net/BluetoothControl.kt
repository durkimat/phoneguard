package io.github.durkimat.phoneguard.net

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.launcher.BtDev

/**
 * Bluetooth from the keypad screens: on and off, the paired devices, a search for new ones, pairing, connecting and forgetting.
 * Android hides some of this from ordinary apps (switching it on, forgetting a device, connecting a headset); where it does, the
 * call is tried and a clear message is given if it is refused.
 */
@Suppress("MissingPermission")
object BluetoothControl {
    private fun adapter(c: Context): BluetoothAdapter? = c.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile private var found = LinkedHashMap<String, BtDev>()
    @Volatile private var receiver: BroadcastReceiver? = null

    fun available(c: Context) = adapter(c) != null
    fun enabled(c: Context): Boolean = try { adapter(c)?.isEnabled == true } catch (_: Exception) { false }

    /** Switch Bluetooth on or off: an app may no longer do this, so a device owner tries, and otherwise asks Android to. */
    fun setEnabled(c: Context, on: Boolean): String = try {
        val a = adapter(c)!!
        @Suppress("DEPRECATION")
        val ok = if (on) a.enable() else a.disable()
        if (ok) "" else "Android refused: use Android settings"
    } catch (e: Exception) { Log.log(c, "bluetooth switch: ${e.javaClass.simpleName}"); "Could not switch" }

    private fun connected(c: Context): Set<String> = try {
        val m = c.applicationContext.getSystemService(BluetoothManager::class.java)
        (listOf(android.bluetooth.BluetoothProfile.A2DP, android.bluetooth.BluetoothProfile.HEADSET, android.bluetooth.BluetoothProfile.GATT)
            .flatMap { m.getConnectedDevices(it) }).map { it.address }.toSet()
    } catch (_: Exception) { emptySet() }

    /** Paired devices first (connected ones on top), then what the current search has found. */
    fun list(c: Context): List<BtDev> = try {
        val a = adapter(c)
        val on = connected(c)
        val bonded = (a?.bondedDevices ?: emptySet()).map { BtDev(it.address, it.name ?: it.address, true, it.address in on, it.bluetoothClass?.majorDeviceClass ?: 0) }
        val extra = found.values.filter { f -> bonded.none { it.address == f.address } }
        bonded.sortedWith(compareBy({ !it.connected }, { it.name.lowercase() })) + extra
    } catch (e: Exception) { Log.log(c, "bluetooth list: ${e.javaClass.simpleName}"); emptyList() }

    /** Start looking for devices; they appear in [list] as they are found. */
    fun search(c: Context) {
        try {
            val a = adapter(c) ?: return
            found = LinkedHashMap()
            if (receiver == null) {
                val r = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, i: Intent) {
                        if (i.action == BluetoothDevice.ACTION_FOUND) {
                            val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                            val name = try { d.name } catch (_: Exception) { null }
                            if (!name.isNullOrBlank()) found[d.address] = BtDev(d.address, name, d.bondState == BluetoothDevice.BOND_BONDED, false, d.bluetoothClass?.majorDeviceClass ?: 0)
                        }
                    }
                }
                c.applicationContext.registerReceiver(r, IntentFilter(BluetoothDevice.ACTION_FOUND), Context.RECEIVER_EXPORTED)
                receiver = r
            }
            if (a.isDiscovering) a.cancelDiscovery()
            a.startDiscovery()
        } catch (e: Exception) { Log.log(c, "bluetooth search: ${e.javaClass.simpleName}") }
    }

    fun stopSearch(c: Context) {
        try { adapter(c)?.cancelDiscovery() } catch (_: Exception) { }
        receiver?.let { try { c.applicationContext.unregisterReceiver(it) } catch (_: Exception) { } }
        receiver = null
    }

    private fun device(c: Context, address: String): BluetoothDevice? = try { adapter(c)?.getRemoteDevice(address) } catch (_: Exception) { null }

    fun pair(c: Context, address: String): Boolean = try { adapter(c)?.cancelDiscovery(); device(c, address)?.createBond() == true } catch (e: Exception) { Log.log(c, "bluetooth pair: ${e.javaClass.simpleName}"); false }

    /** Forget a paired device (a hidden call; reported if Android refuses). */
    fun forget(c: Context, address: String): Boolean {
        val d = device(c, address) ?: return false
        return try { BluetoothDevice::class.java.getMethod("removeBond").invoke(d) as? Boolean ?: false }
        catch (e: Exception) { Log.log(c, "bluetooth forget: ${e.javaClass.simpleName}"); false }
    }

    /** Connect or disconnect a paired audio device through its profiles (hidden calls; reported if Android refuses). */
    fun setConnected(c: Context, address: String, connect: Boolean): Boolean {
        val d = device(c, address) ?: return false
        var ok = false
        for (profile in listOf(android.bluetooth.BluetoothProfile.A2DP, android.bluetooth.BluetoothProfile.HEADSET)) {
            val latch = java.util.concurrent.CountDownLatch(1)
            try {
                adapter(c)?.getProfileProxy(c.applicationContext, object : android.bluetooth.BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: android.bluetooth.BluetoothProfile) {
                        try {
                            val m = proxy.javaClass.getMethod(if (connect) "connect" else "disconnect", BluetoothDevice::class.java)
                            m.isAccessible = true
                            if (m.invoke(proxy, d) as? Boolean == true) ok = true
                        } catch (e: Exception) { Log.log(c, "bluetooth ${if (connect) "connect" else "disconnect"}: ${e.javaClass.simpleName}") }
                        try { adapter(c)?.closeProfileProxy(p, proxy) } catch (_: Exception) { }
                        latch.countDown()
                    }
                    override fun onServiceDisconnected(p: Int) { latch.countDown() }
                }, profile)
                latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) { Log.log(c, "bluetooth profile: ${e.javaClass.simpleName}") }
        }
        return ok
    }
}
