package com.squatalarm.app.squat

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.squatalarm.app.alarm.AlarmPrefs
import kotlin.math.sqrt

/**
 * Lee los sensores, proyecta la aceleración lineal sobre la vertical (vale en cualquier orientación)
 * y alimenta un [SquatCounter]. El teléfono se sostiene contra el pecho.
 */
class SquatDetector(
    context: Context,
    val counter: SquatCounter,
    private val listener: Listener,
) : SensorEventListener {

    interface Listener {
        fun onSquat(count: Int, squat: SquatCounter.Squat)
        fun onSample(aVert: Float, state: SquatCounter.State) {}
    }

    /** Recibe cada muestra vertical sin filtrar (m/s², ms) para grabarla y re-analizarla después. */
    var recorder: ((Float, Long) -> Unit)? = null

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val linearSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val useFused = gravitySensor != null && linearSensor != null

    val isAvailable: Boolean get() = useFused || accelSensor != null

    private val gravity = FloatArray(3)
    private var hasGravity = false

    /** Arranca los sensores; no reinicia el contador (así la alarma conserva el progreso si la pantalla se apaga). */
    fun start() {
        hasGravity = false
        if (useFused) {
            sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
            sensorManager.registerListener(this, linearSensor, SensorManager.SENSOR_DELAY_GAME)
        } else if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        val t = event.timestamp / 1_000_000L
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gravity[0] = event.values[0]; gravity[1] = event.values[1]; gravity[2] = event.values[2]
                hasGravity = true
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> if (hasGravity) feed(vertical(event.values), t)
            Sensor.TYPE_ACCELEROMETER -> {
                // Respaldo sin sensores fusionados: gravedad = paso bajo lento del acelerómetro.
                if (!hasGravity) {
                    gravity[0] = event.values[0]; gravity[1] = event.values[1]; gravity[2] = event.values[2]
                    hasGravity = true
                }
                for (i in 0..2) gravity[i] += GRAVITY_ALPHA * (event.values[i] - gravity[i])
                val lin = floatArrayOf(
                    event.values[0] - gravity[0],
                    event.values[1] - gravity[1],
                    event.values[2] - gravity[2],
                )
                feed(vertical(lin), t)
            }
        }
    }

    private fun feed(raw: Float, t: Long) {
        recorder?.invoke(raw, t)
        val squat = counter.process(raw, t)
        if (squat != null) listener.onSquat(counter.count, squat)
        listener.onSample(counter.filtered, counter.state)
    }

    /** Proyección de la aceleración lineal sobre la vertical (positivo = hacia arriba). */
    private fun vertical(lin: FloatArray): Float {
        val g = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
        if (g < 1e-3f) return 0f
        return (lin[0] * gravity[0] + lin[1] * gravity[1] + lin[2] * gravity[2]) / g
    }

    companion object {
        private const val GRAVITY_ALPHA = 0.04f

        /** Detector con la calibración guardada × exigencia elegida. */
        fun fromPrefs(context: Context, prefs: AlarmPrefs, listener: Listener): SquatDetector {
            val c = prefs.calibration
            val s = prefs.strictness
            return SquatDetector(
                context,
                SquatCounter(c.thresholdDown * s, c.thresholdUp * s, c.minCycleMs, c.maxCycleMs),
                listener,
            )
        }
    }
}
