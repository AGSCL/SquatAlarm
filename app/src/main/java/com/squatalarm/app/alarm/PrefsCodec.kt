package com.squatalarm.app.alarm

/**
 * Archivo de respaldo de parámetros: texto plano, una línea `clave=valor`. Kotlin puro (se prueba
 * en core-check). Solo se leen las claves del esquema y cada valor se valida con su tipo: un archivo
 * editado a mano no puede guardar, p. ej., un texto donde la app espera un número.
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
        val lines = text.lines()
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
            out[key] = when (type) {
                'b' -> v.trim().toBooleanStrictOrNull()
                'i' -> v.trim().toIntOrNull()
                'l' -> v.trim().toLongOrNull()
                'f' -> v.trim().toFloatOrNull()?.takeIf { it.isFinite() }
                else -> v
            } ?: throw IllegalArgumentException("Valor inválido para $key: $v")
        }
        return out
    }
}

/** Regla de la misión «ir hasta el sensor»: ¿esta notificación apaga la alarma? */
object SensorMatch {
    fun matches(pkg: String, title: String, text: String, postTime: Long, wantPkg: String, wantText: String, since: Long): Boolean =
        wantPkg.isNotEmpty() && wantText.isNotBlank() && pkg == wantPkg && postTime >= since &&
            "$title $text".contains(wantText.trim(), ignoreCase = true)
}
