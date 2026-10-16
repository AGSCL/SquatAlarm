package com.squatalarm.app.alarm

/**
 * Archivo de respaldo de parámetros: texto plano, una línea `clave=valor`. Kotlin puro (se prueba
 * en core-check). Solo se leen las claves del esquema y cada valor se valida con su tipo y su rango: un
 * archivo editado a mano no puede guardar, p. ej., un texto donde la app espera un número.
 */
object PrefsCodec {
    const val HEADER = "# SquatAlarm parametros v1"

    /** Esquema: clave → tipo (b = Boolean, i = Int, l = Long, f = Float, s = String). */
    val SCHEMA: Map<String, Char> = linkedMapOf(
        "enabled" to 'b', "hour" to 'i', "minute" to 'i', "repeat_days" to 'i', "squats_required" to 'i',
        "strictness" to 'f', "safety_timeout_min" to 'i', "mission" to 'i', "sensor_package" to 's', "sensor_text" to 's',
        "cal_thr_down" to 'f', "cal_thr_up" to 'f', "cal_min_cycle" to 'l', "cal_max_cycle" to 'l',
        "cal_max_peak" to 'f', "cal_tilt_min" to 'f', "cal_tilt_max" to 'f', "cal_done" to 'b', "cal_n" to 'i',
        "cal_sens" to 'f', "cal_spec" to 'f', "cal_val_sens" to 'f', "cal_val_spec" to 'f',
        "soft_start" to 'b', "sound_soft" to 's', "sound_loud" to 's', "volume_soft" to 'f', "volume_loud" to 'f',
    )

    /**
     * Rangos válidos (los de la interfaz). Un archivo editado a mano con, p. ej., exigencia 0 o 100 000
     * sentadillas dejaría la alarma inútil o sin forma de apagarse.
     */
    val RANGES: Map<String, ClosedFloatingPointRange<Double>> = mapOf(
        "hour" to 0.0..23.0, "minute" to 0.0..59.0, "repeat_days" to 0.0..127.0, "squats_required" to 3.0..40.0,
        "strictness" to 0.5..1.8, "safety_timeout_min" to 5.0..180.0, "mission" to 0.0..1.0,
        "cal_thr_down" to 0.1..10.0, "cal_thr_up" to 0.1..10.0, "cal_min_cycle" to 0.0..5000.0, "cal_max_cycle" to 500.0..20000.0,
        "cal_max_peak" to 0.1..1000.0, /* 1000 = sin límite */ "cal_tilt_min" to 0.0..180.0, "cal_tilt_max" to 0.0..180.0,
        "cal_n" to 0.0..10000.0, "cal_sens" to -1.0..1.0, "cal_spec" to -1.0..1.0, "cal_val_sens" to -1.0..1.0, "cal_val_spec" to -1.0..1.0,
        "volume_soft" to 0.05..0.6, "volume_loud" to 0.3..1.0,
    )

    fun encode(values: Map<String, *>): String = buildString {
        append(HEADER).append('\n')
        for (key in SCHEMA.keys) {
            val v = values[key] ?: continue
            append(key).append('=').append(v.toString().replace('\n', ' ')).append('\n')
        }
    }

    /** Devuelve los valores ya tipados; lanza IllegalArgumentException si el archivo no es válido. */
    fun decode(text: String): Map<String, Any> {
        val lines = text.removePrefix("\uFEFF").lines() // BOM de algunos editores (Bloc de notas)
        require(lines.firstOrNull()?.trim() == HEADER) { "No es un archivo de parámetros de SquatAlarm" }
        val out = LinkedHashMap<String, Any>()
        for (raw in lines.drop(1)) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            require(eq > 0) { "Línea inválida: $line" }
            val key = line.substring(0, eq).trim()
            val v = line.substring(eq + 1)
            val type = SCHEMA[key] ?: continue // clave desconocida (otra versión): se ignora
            val value: Any = when (type) {
                'b' -> v.trim().toBooleanStrictOrNull()
                'i' -> v.trim().toIntOrNull()
                'l' -> v.trim().toLongOrNull()
                'f' -> v.trim().toFloatOrNull()?.takeIf { it.isFinite() }
                else -> v
            } ?: throw IllegalArgumentException("Valor inválido para $key: $v")
            val range = RANGES[key]
            val inRange = when {
                range == null -> true
                value is Float -> value in range.start.toFloat()..range.endInclusive.toFloat() // 0,6f es 0,6000000238 en Double
                else -> (value as Number).toDouble() in range
            }
            require(inRange) { "Valor fuera de rango para $key: $v" }
            out[key] = value
        }
        return out
    }
}

/** Regla de la misión «ir hasta el sensor»: ¿esta notificación apaga la alarma? */
object SensorMatch {
    /** [wantPkg] vacío = cualquier app (notificación escrita a mano sin elegir la app). */
    fun matches(pkg: String, title: String, text: String, postTime: Long, wantPkg: String, wantText: String, since: Long): Boolean =
        wantText.isNotBlank() && (wantPkg.isEmpty() || pkg == wantPkg) && postTime >= since &&
            "$title $text".contains(wantText.trim(), ignoreCase = true)
}
