package com.squatalarm.app.alarm

import android.content.Context
import android.content.SharedPreferences
import com.squatalarm.app.squat.Calibration

/** Configuración persistente (SharedPreferences). */
class AlarmPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("squat_alarm", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var hour: Int
        get() = sp.getInt("hour", 7)
        set(v) = sp.edit().putInt("hour", v).apply()

    var minute: Int
        get() = sp.getInt("minute", 0)
        set(v) = sp.edit().putInt("minute", v).apply()

    /** Máscara de días: bit (Calendar.DAY_OF_WEEK - 1). 0 = suena una sola vez. */
    var repeatDays: Int
        get() = sp.getInt("repeat_days", 0)
        set(v) = sp.edit().putInt("repeat_days", v).apply()

    var squatsRequired: Int
        get() = sp.getInt("squats_required", 10)
        set(v) = sp.edit().putInt("squats_required", v).apply()

    /** Multiplica los umbrales calibrados: <1 cuenta más fácil, >1 más exigente. */
    var strictness: Float
        get() = sp.getFloat("strictness", 1f)
        set(v) = sp.edit().putFloat("strictness", v).apply()

    /** Red de seguridad: tras estos minutos la alarma se apaga sola (evita un bucle infinito si la detección falla). */
    var safetyTimeoutMin: Int
        get() = sp.getInt("safety_timeout_min", DEFAULT_SAFETY_TIMEOUT_MIN)
        set(v) = sp.edit().putInt("safety_timeout_min", v).apply()

    var isRinging: Boolean
        get() = sp.getBoolean("is_ringing", false)
        set(v) = sp.edit().putBoolean("is_ringing", v).apply()

    /** true cuando sonó por "Probar alarma": muestra un botón de salida. */
    var isTestRing: Boolean
        get() = sp.getBoolean("is_test_ring", false)
        set(v) = sp.edit().putBoolean("is_test_ring", v).apply()

    var ringingSince: Long
        get() = sp.getLong("ringing_since", 0L)
        set(v) = sp.edit().putLong("ringing_since", v).apply()

    var scheduledAt: Long
        get() = sp.getLong("scheduled_at", 0L)
        set(v) = sp.edit().putLong("scheduled_at", v).apply()

    var calibration: Calibration
        get() = Calibration(
            thresholdDown = sp.getFloat("cal_thr_down", Calibration.DEFAULT.thresholdDown),
            thresholdUp = sp.getFloat("cal_thr_up", Calibration.DEFAULT.thresholdUp),
            minCycleMs = sp.getLong("cal_min_cycle", Calibration.DEFAULT.minCycleMs),
            maxCycleMs = sp.getLong("cal_max_cycle", Calibration.DEFAULT.maxCycleMs),
            maxPeak = sp.getFloat("cal_max_peak", Calibration.DEFAULT.maxPeak),
            tiltMinDeg = sp.getFloat("cal_tilt_min", Calibration.DEFAULT.tiltMinDeg),
            tiltMaxDeg = sp.getFloat("cal_tilt_max", Calibration.DEFAULT.tiltMaxDeg),
            calibrated = sp.getBoolean("cal_done", false),
            nSquats = sp.getInt("cal_n", 0),
            sens = sp.getFloat("cal_sens", -1f),
            spec = sp.getFloat("cal_spec", -1f),
            valSens = sp.getFloat("cal_val_sens", -1f),
            valSpec = sp.getFloat("cal_val_spec", -1f),
        )
        set(c) = sp.edit()
            .putFloat("cal_thr_down", c.thresholdDown)
            .putFloat("cal_thr_up", c.thresholdUp)
            .putLong("cal_min_cycle", c.minCycleMs)
            .putLong("cal_max_cycle", c.maxCycleMs)
            .putFloat("cal_max_peak", c.maxPeak)
            .putFloat("cal_tilt_min", c.tiltMinDeg)
            .putFloat("cal_tilt_max", c.tiltMaxDeg)
            .putBoolean("cal_done", c.calibrated)
            .putInt("cal_n", c.nSquats)
            .putFloat("cal_sens", c.sens)
            .putFloat("cal_spec", c.spec)
            .putFloat("cal_val_sens", c.valSens)
            .putFloat("cal_val_spec", c.valSpec)
            .apply()

    companion object {
        const val DEFAULT_SAFETY_TIMEOUT_MIN = 60
    }
}
