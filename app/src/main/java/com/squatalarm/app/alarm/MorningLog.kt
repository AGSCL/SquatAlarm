package com.squatalarm.app.alarm

import android.content.Context
import com.squatalarm.app.squat.BayesTuner
import com.squatalarm.app.squat.Segment
import com.squatalarm.app.squat.Tuner
import java.io.File

/**
 * Registro de mañanas (CSV) y aprendizaje desde las alarmas. Todo en el almacenamiento protegido por dispositivo,
 * como los parámetros: una alarma apagada antes del primer desbloqueo también se registra.
 */
object MorningLog {
    /** La alarma aprende solo de sus últimos minutos: las sentadillas, no el rato acostado. */
    const val ALARM_WINDOW_MS = 180_000L

    private fun dir(context: Context) = context.createDeviceProtectedStorageContext().filesDir
    fun logFile(context: Context) = File(dir(context), "registro.csv")
    private fun posteriorFile(context: Context) = File(dir(context), "posterior.bin")

    fun rows(context: Context): List<CheckIn> =
        try { logFile(context).readLines().mapNotNull(CheckIn::parse) } catch (e: Exception) { emptyList() }

    fun append(context: Context, row: CheckIn) {
        val f = logFile(context)
        if (!f.exists()) f.writeText(CheckIn.HEADER + "\n")
        f.appendText(row.toCsv() + "\n")
    }

    fun hasPosterior(context: Context) = posteriorFile(context).exists()

    /** Escribe a un temporal y lo renombra: si el proceso muere a mitad, queda el archivo anterior entero. */
    fun savePosterior(context: Context, tuner: BayesTuner) {
        val f = posteriorFile(context)
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().buffered().use { tuner.write(it) }
        if (!tmp.renameTo(f)) tmp.delete()
    }

    private fun loadPosterior(context: Context): BayesTuner? =
        try { posteriorFile(context).inputStream().buffered().use { BayesTuner.read(it) } } catch (e: Exception) { null }

    /**
     * Suma la alarma a los posteriores de la preconfiguración (peso [Tuner.ALARM_EVIDENCE]) y cambia la detección
     * solo si otra θ es claramente mejor. Devuelve qué pasó, para mostrarlo y guardarlo en el registro.
     */
    fun learn(context: Context, seg: Segment, real: Int, counted: Int): String {
        val prefs = AlarmPrefs(context)
        val cal = prefs.calibration
        val cur = BayesTuner.indexOf(cal)
        val tuner = if (cal.calibrated && cur >= 0) loadPosterior(context) else null
        if (tuner == null) return "sin aprender: falta preconfigurar con esta versión"
        seg.dropBefore(seg.startMs + seg.durationMs - ALARM_WINDOW_MS)
        if (seg.durationMs < 10_000L) return "sin aprender: no hay datos de sensores"
        seg.truth = real
        seg.liveCount = counted
        val w = prefs.calWeight.toDouble()
        val new = Tuner.learnFromAlarm(tuner, seg, w, cur)
        savePosterior(context, tuner)
        prefs.learnedAlarms = prefs.learnedAlarms + 1
        val i = new ?: cur
        val post = tuner.posterior(i)
        // Si cambia θ, la validación anterior ya no corresponde: queda «sin validar».
        prefs.calibration = BayesTuner.calibration(i).copy(
            calibrated = true, nSquats = cal.nSquats, sens = post.sens.toFloat(), spec = post.spec.toFloat(),
            valSens = if (new == null) cal.valSens else -1f, valSpec = if (new == null) cal.valSpec else -1f,
        )
        return if (new == null) "aprendió; detección sin cambios" else "cambió la detección (claramente mejor)"
    }
}
