package com.squatalarm.app.alarm

/**
 * Registro de una mañana: una fila del CSV que el usuario puede exportar. null = sin responder.
 * Kotlin puro (se prueba en core-check).
 */
data class CheckIn(
    val date: String,
    val time: String,
    val ringAt: Long,
    val minutesToDismiss: Double,
    val mission: String,
    val counted: Int?,
    val real: Int?,
    /** 1 = 🙂, 0 = 😐, -1 = ☹ */
    val mood: Int?,
    val alcohol: Boolean?,
    val smoked: Boolean?,
    val bedtime: String?,
    val hoursInBed: Double?,
    val stressed: Boolean?,
    val learned: String,
) {
    fun toCsv(): String = listOf(
        date, time, ringAt.toString(), round1(minutesToDismiss), mission, counted?.toString().orEmpty(), real?.toString().orEmpty(),
        mood?.toString().orEmpty(), yesNo(alcohol), yesNo(smoked), bedtime.orEmpty(), hoursInBed?.let(::round1).orEmpty(),
        yesNo(stressed), learned.replace(',', ';').replace('\n', ' '),
    ).joinToString(",")

    companion object {
        const val HEADER = "fecha,hora,inicio_ms,minutos_hasta_apagar,mision,contadas,reales,animo,alcohol,tabaco," +
            "acostarse,horas_en_cama,estres,aprendizaje"

        /** Una línea del CSV; null si no se puede leer (encabezado o línea dañada). */
        fun parse(line: String): CheckIn? = try {
            val c = line.split(',')
            CheckIn(
                c[0], c[1], c[2].toLong(), c[3].toDouble(), c[4], c[5].toIntOrNull(), c[6].toIntOrNull(), c[7].toIntOrNull(),
                bool(c[8]), bool(c[9]), c[10].ifEmpty { null }, c[11].toDoubleOrNull(), bool(c[12]), c[13],
            )
        } catch (e: Exception) {
            null
        }

        /** Horas en cama: de la hora de acostarse a la de la alarma, cruzando la medianoche (minutos desde las 00:00). */
        fun hoursInBed(bedMin: Int, ringMin: Int): Double = ((ringMin - bedMin + 1440) % 1440) / 60.0

        fun hhmm(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

        private fun round1(x: Double) = (Math.round(x * 10) / 10.0).toString()
        private fun yesNo(b: Boolean?) = when (b) { true -> "si"; false -> "no"; null -> "" }
        private fun bool(s: String) = when (s) { "si" -> true; "no" -> false; else -> null }
    }
}

/** Resumen para la app: ánimo y minutos hasta apagarla según cada factor (sí / no). */
object MorningSummary {
    class Group(val n: Int, val mood: Double?, val minutes: Double?)
    class Factor(val name: String, val yes: Group, val no: Group)

    fun factors(rows: List<CheckIn>): List<Factor> = listOf(
        factor("Alcohol", rows) { it.alcohol },
        factor("Tabaco", rows) { it.smoked },
        factor("Estrés", rows) { it.stressed },
        factor("7 h o más en cama", rows) { r -> r.hoursInBed?.let { it >= 7.0 } },
    )

    private fun factor(name: String, rows: List<CheckIn>, answer: (CheckIn) -> Boolean?): Factor {
        fun group(v: Boolean): Group {
            val g = rows.filter { answer(it) == v }
            val moods = g.mapNotNull { it.mood?.toDouble() }
            return Group(g.size, moods.takeIf { it.isNotEmpty() }?.average(), g.takeIf { it.isNotEmpty() }?.map { it.minutesToDismiss }?.average())
        }
        return Factor(name, group(true), group(false))
    }
}
