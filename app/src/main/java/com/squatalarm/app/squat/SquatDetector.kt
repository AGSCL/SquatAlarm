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
 * y alimenta un [SquatCounter] junto con la orientación del teléfono. El teléfono va contra el pecho.
 */
class SquatDetector(
    context: Context,
    val counter: SquatCounter,
    private val listener: Listener,
) : SensorEventListener {

    interface Listener {
        fun onSquat(count: Int, squat: SquatCounter.Squat)
        fun onRejected(squat: SquatCounter.Squat) {}
        fun onSample(aVert: Float, state: SquatCounter.State) {}
    }

    /** Recibe cada muestra tal como entra al contador (a, t, gravedad unitaria) para re-analizarla después. */
    var recorder: ((Float, Long, Float, Float, Float) -> Unit)? = null

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
            Sensor.TYPE_LINEAR_ACCELERATION -> if (hasGravity) feed(event.values[0], event.values[1], event.values[2], t)
            Sensor.TYPE_ACCELEROMETER -> {
                // Respaldo sin sensores fusionados: gravedad = paso bajo lento del acelerómetro.
                if (!hasGravity) {
                    gravity[0] = event.values[0]; gravity[1] = event.values[1]; gravity[2] = event.values[2]
                    hasGravity = true
                }
                for (i in 0..2) gravity[i] += GRAVITY_ALPHA * (event.values[i] - gravity[i])
                feed(event.values[0] - gravity[0], event.values[1] - gravity[1], event.values[2] - gravity[2], t)
            }
        }
    }

    /** Aceleración lineal (lx, ly, lz) → vertical (+ = arriba) y gravedad unitaria → contador. */
    private fun feed(lx: Float, ly: Float, lz: Float, t: Long) {
        val g = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
        if (g < 1e-3f) return
        val ux = gravity[0] / g
        val uy = gravity[1] / g
        val uz = gravity[2] / g
        val a = lx * ux + ly * uy + lz * uz
        recorder?.invoke(a, t, ux, uy, uz)
        val squat = counter.process(a, t, ux, uy, uz)
        if (squat != null) {
            if (squat.reject == SquatCounter.Reject.NONE) listener.onSquat(counter.count, squat)
            else listener.onRejected(squat)
        }
        listener.onSample(counter.filtered, counter.state)
    }

    companion object {
        private const val GRAVITY_ALPHA = 0.04f

        /** Detector con la calibración guardada × exigencia elegida. */
        fun fromPrefs(context: Context, prefs: AlarmPrefs, listener: Listener): SquatDetector =
            SquatDetector(context, prefs.calibration.counter(prefs.strictness), listener)
    }
}
