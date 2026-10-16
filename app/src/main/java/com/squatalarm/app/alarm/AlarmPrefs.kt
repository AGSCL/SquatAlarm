package com.squatalarm.app.alarm

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import com.squatalarm.app.squat.Calibration

/** Configuración persistente (SharedPreferences en almacenamiento protegido por dispositivo, ver [open]). */
class AlarmPrefs(context: Context) {

    private val sp: SharedPreferences = open(context.applicationContext)

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

    /** Cómo se apaga: [MISSION_SQUATS] o [MISSION_SENSOR] (ir hasta un sensor que manda una notificación). */
    var mission: Int
        get() = sp.getInt("mission", MISSION_SQUATS)
        set(v) = sp.edit().putInt("mission", v).apply()

    /** Paquete de la app del sensor (p. ej. SwitchBot) y texto que debe traer su notificación. */
    var sensorPackage: String
        get() = sp.getString("sensor_package", "") ?: ""
        set(v) = sp.edit().putString("sensor_package", v).apply()

    var sensorText: String
        get() = sp.getString("sensor_text", "") ?: ""
        set(v) = sp.edit().putString("sensor_text", v).apply()

    /** Inicio suave (ver [RingPlan]): vibración y sonido bajo, silencio y después la alarma completa. */
    var softStart: Boolean
        get() = sp.getBoolean("soft_start", true)
        set(v) = sp.edit().putBoolean("soft_start", v).apply()

    /** URI del sonido de cada fase ("" = alarma predeterminada) y su volumen (fracción del máximo de alarma). */
    var soundSoft: String
        get() = sp.getString("sound_soft", "") ?: ""
        set(v) = sp.edit().putString("sound_soft", v).apply()

    var soundLoud: String
        get() = sp.getString("sound_loud", "") ?: ""
        set(v) = sp.edit().putString("sound_loud", v).apply()

    var volumeSoft: Float
        get() = sp.getFloat("volume_soft", 0.2f)
        set(v) = sp.edit().putFloat("volume_soft", v).apply()

    var volumeLoud: Float
        get() = sp.getFloat("volume_loud", 1f)
        set(v) = sp.edit().putFloat("volume_loud", v).apply()

    /** Respaldo de todos los parámetros (no el estado de una alarma sonando). */
    fun exportText(): String = PrefsCodec.encode(sp.all)

    /** Lanza IllegalArgumentException si el archivo no es válido; en ese caso no cambia nada. */
    fun importText(text: String) {
        val values = PrefsCodec.decode(text)
        val e = sp.edit()
        for ((k, v) in values) {
            when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is String -> e.putString(k, v)
            }
        }
        e.apply()
    }

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

    /** Sonando de verdad: si el proceso se detuvo a la fuerza, is_ringing queda en true con una hora vieja. */
    fun ringingActive(now: Long = System.currentTimeMillis()): Boolean =
        isRinging && now - ringingSince in 0L..safetyTimeoutMin * 60_000L

    /** Volumen de alarma antes de sonar (-1 = nada que restaurar). Persistido: sobrevive a un reinicio a media alarma. */
    var volumeBefore: Int
        get() = sp.getInt("volume_before", -1)
        set(v) = sp.edit().putInt("volume_before", v).apply()

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
        const val MISSION_SQUATS = 0
        const val MISSION_SENSOR = 1
        private const val FILE = "squat_alarm"
        @Volatile private var shared: SharedPreferences? = null

        /**
         * Almacenamiento protegido por dispositivo: se lee antes del primer desbloqueo tras reiniciar (Direct Boot),
         * así la alarma se reprograma y suena aunque nadie haya puesto el PIN.
         */
        private fun open(app: Context): SharedPreferences {
            shared?.let { return it }
            val dp = app.createDeviceProtectedStorageContext()
            val unlocked = app.getSystemService(UserManager::class.java).isUserUnlocked
            if (unlocked) dp.moveSharedPreferencesFrom(app, FILE) // trae los parámetros de versiones anteriores (no hace nada si ya se movieron)
            return dp.getSharedPreferences(FILE, Context.MODE_PRIVATE).also { if (unlocked) shared = it }
        }
    }
}
