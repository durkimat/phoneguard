package io.github.durkimat.phoneguard.keys

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Whether the front of the phone is covered (in a pocket or bag), read only around a side-button press so nothing listens the rest of the time:
 * [watch] starts listening at the key-down, [release] stops it once the gesture is decided. No sensor, or no reading yet, counts as not covered.
 */
object Proximity {
    @Volatile var covered = false
        private set
    private var listener: SensorEventListener? = null

    @Synchronized fun watch(c: Context) {
        if (listener != null) return
        val sm = c.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return
        covered = false
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { covered = e.values[0] < minOf(5f, sensor.maximumRange) }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        try { sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL); listener = l } catch (_: Exception) { }
    }

    @Synchronized fun release(c: Context) {
        val l = listener ?: return
        try { c.getSystemService(SensorManager::class.java)?.unregisterListener(l) } catch (_: Exception) { }
        listener = null
    }
}
