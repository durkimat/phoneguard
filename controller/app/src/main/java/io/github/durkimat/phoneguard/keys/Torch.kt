package io.github.durkimat.phoneguard.keys

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import io.github.durkimat.phoneguard.Log

/** The LED torch, switched on and off without waking the screen. It goes off by itself after ten minutes so it is never left on. */
object Torch {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var on = false
        private set
    private var registered = false
    private var autoOff: Runnable? = null
    private val listeners = ArrayList<() -> Unit>()

    fun onChange(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun offChange(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun fire() { val ls = synchronized(listeners) { ArrayList(listeners) }; main.post { ls.forEach { it() } } }

    private fun cameraId(m: CameraManager): String? =
        m.cameraIdList.firstOrNull { m.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
            m.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }

    private fun watch(c: Context) {
        if (registered) return
        registered = true
        val m = c.getSystemService(CameraManager::class.java)
        m.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (on != enabled) { on = enabled; fire() } }
        }, main)
    }

    fun toggle(c: Context) { watch(c); set(c, !on) }

    fun set(c: Context, enable: Boolean) {
        try {
            watch(c)
            val m = c.getSystemService(CameraManager::class.java)
            val id = cameraId(m) ?: return Log.log(c, "torch: no flash")
            m.setTorchMode(id, enable)
            on = enable; fire()
            autoOff?.let { main.removeCallbacks(it) }
            if (enable) { val r = Runnable { set(c, false) }; autoOff = r; main.postDelayed(r, 10 * 60_000L) }
        } catch (e: Exception) { Log.log(c, "torch: ${e.javaClass.simpleName} ${e.message?.take(60)}") }
    }
}
