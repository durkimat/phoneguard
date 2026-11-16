package io.github.durkimat.phoneguard.where

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

/**
 * The child's dot and which way the phone faces, only while the "Where to?" map is open: location updates every few seconds (when the mode keeps
 * location on; otherwise there are none and the map says so) and the rotation sensor for the heading (the GPS bearing instead when moving faster
 * than about 1 m/s). Nothing listens once [stop] is called, which the launcher does when the map is left.
 */
object WhereTracker {
    class Fix(val lat: Double, val lon: Double)
    private var running = false
    private var loc: LocationListener? = null
    private var sensor: SensorEventListener? = null
    private var lastFix: Location? = null
    private var heading: Float? = null
    private var lastHeadingAt = 0L

    @Synchronized fun start(c: Context, onChange: (Fix?, Float?) -> Unit) {
        if (running) return
        running = true; lastFix = null; heading = null
        try {
            val lm = c.getSystemService(LocationManager::class.java)
            if (lm.isLocationEnabled) {
                val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
                val l = object : LocationListener {
                    override fun onLocationChanged(l: Location) {
                        lastFix = l
                        if (l.hasBearing() && l.hasSpeed() && l.speed > 1f) heading = l.bearing
                        onChange(Fix(l.latitude, l.longitude), heading)
                    }
                    @Deprecated("old callback") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                lm.requestLocationUpdates(provider, 5_000L, 0f, l, Looper.getMainLooper())
                loc = l
                lm.getLastKnownLocation(provider)?.let { if (System.currentTimeMillis() - it.time < 20 * 60_000L) { lastFix = it; onChange(Fix(it.latitude, it.longitude), heading) } }
            }
        } catch (_: SecurityException) { } catch (_: Exception) { }
        try {
            val sm = c.getSystemService(SensorManager::class.java)
            val s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (s != null) {
                val l = object : SensorEventListener {
                    private val m = FloatArray(9); private val o = FloatArray(3)
                    override fun onSensorChanged(e: SensorEvent) {
                        val now = System.currentTimeMillis()
                        if (now - lastHeadingAt < 500) return
                        lastHeadingAt = now
                        val moving = lastFix?.let { it.hasSpeed() && it.speed > 1f && it.hasBearing() } == true
                        if (moving) return // the GPS bearing is better while walking
                        SensorManager.getRotationMatrixFromVector(m, e.values); SensorManager.getOrientation(m, o)
                        heading = ((Math.toDegrees(o[0].toDouble()).toFloat() % 360f) + 360f) % 360f
                        onChange(lastFix?.let { Fix(it.latitude, it.longitude) }, heading)
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)
                sensor = l
            }
        } catch (_: Exception) { }
    }

    @Synchronized fun stop(c: Context) {
        if (!running) return
        running = false
        try { loc?.let { c.getSystemService(LocationManager::class.java).removeUpdates(it) } } catch (_: Exception) { }
        try { sensor?.let { c.getSystemService(SensorManager::class.java).unregisterListener(it) } } catch (_: Exception) { }
        loc = null; sensor = null
    }
}
