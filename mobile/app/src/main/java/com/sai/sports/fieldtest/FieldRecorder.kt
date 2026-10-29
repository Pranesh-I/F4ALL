package com.sai.sports.fieldtest

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import java.util.Collections

/**
 * Records a run: accelerometer and gyroscope (and, for the endurance run,
 * GPS), timed against the GO beep.
 *
 * Every timestamp is on the uptime clock the sensors and GPS use
 * (`elapsedRealtimeNanos`), so nothing depends on the phone's settable clock,
 * and [goAtNanos] turns each into milliseconds from GO — negative during the
 * countdown, which is how the analyzers see the athlete standing still.
 */
class FieldRecorder(context: Context, private val testType: FieldTestType) {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val locations = context.getSystemService(LocationManager::class.java)

    private val motion = Collections.synchronizedList(mutableListOf<Motion>())
    private val fixes = Collections.synchronizedList(mutableListOf<Fix>())

    @Volatile private var goAtNanos: Long = SystemClock.elapsedRealtimeNanos() + 10_000_000_000L
    @Volatile private var gyro = DoubleArray(3)
    @Volatile var latestFix: Location? = null
        private set

    val hasGyroscope: Boolean get() = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> gyro = doubleArrayOf(
                    event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble()
                )
                Sensor.TYPE_ACCELEROMETER -> {
                    val t = (event.timestamp - goAtNanos) / 1_000_000
                    // The endurance run needs steps, not turns: 25 samples a second is plenty.
                    val keepEvery = if (testType == FieldTestType.ENDURANCE_RUN) ENDURANCE_SAMPLE_MS else 0
                    synchronized(motion) {
                        if (keepEvery > 0 && motion.isNotEmpty() && t - motion.last().tMs < keepEvery) return
                        val g = gyro
                        motion += Motion(
                            t, event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble(),
                            g[0], g[1], g[2]
                        )
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val locationListener = LocationListener { location ->
        latestFix = location
        val t = (location.elapsedRealtimeNanos - goAtNanos) / 1_000_000
        fixes += Fix(t, location.latitude, location.longitude, location.accuracy.toDouble())
    }

    /** Starts the sensors; the countdown runs while they record the athlete standing still. */
    fun startSensors() {
        val manager = sensors ?: return
        manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            manager.registerListener(sensorListener, it, SAMPLING_PERIOD_US)
        }
        manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            manager.registerListener(sensorListener, it, SAMPLING_PERIOD_US)
        }
    }

    /** Starts GPS early, so a good fix is ready before the countdown. Needs location permission. */
    @SuppressLint("MissingPermission")
    fun startGps() {
        runCatching {
            locations?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
        }
    }

    /** Marks the GO beep; everything recorded before it becomes negative time. */
    fun markGo() {
        val go = SystemClock.elapsedRealtimeNanos()
        val shift = (goAtNanos - go) / 1_000_000
        goAtNanos = go
        synchronized(motion) {
            val rebased = motion.map { it.copy(tMs = it.tMs + shift) }
            motion.clear()
            motion.addAll(rebased)
        }
        synchronized(fixes) {
            val rebased = fixes.map { it.copy(tMs = it.tMs + shift) }
            fixes.clear()
            fixes.addAll(rebased)
        }
    }

    /** Milliseconds since GO, on the same clock as the samples. */
    fun elapsedMs(): Long = (SystemClock.elapsedRealtimeNanos() - goAtNanos) / 1_000_000

    fun snapshot(): FieldTrace = FieldTrace(
        motion = synchronized(motion) { motion.toList() },
        fixes = synchronized(fixes) { fixes.toList() }
    )

    fun stop() {
        sensors?.unregisterListener(sensorListener)
        runCatching { locations?.removeUpdates(locationListener) }
    }

    companion object {
        /** 100 samples a second: the shuttle analyzer needs a median gap of 25 ms or less. */
        const val SAMPLING_PERIOD_US = 10_000
        const val ENDURANCE_SAMPLE_MS = 40L
    }
}
