package com.squatalarm.app.alarm

import android.content.Context
import com.squatalarm.app.squat.Evolver
import com.squatalarm.app.squat.Segment
import com.squatalarm.app.squat.Tuner
import java.io.File

/**
 * Registro de mañanas (CSV) y aprendizaje desde las alarmas. Todo en el almacenamiento protegido por dispositivo,
 * como los parámetros: una alarma apagada antes del primer desbloqueo también se registra.
 */
object MorningLog {

    private fun dir(context: Context) = context.createDeviceProtectedStorageContext().filesDir
    fun logFile(context: Context) = File(dir(context), "registro.csv")
    private fun posteriorFile(context: Context) = File(dir(context), "aprendizaje.bin")

    fun rows(context: Context): List<CheckIn> =
        try { logFile(context).readLines().mapNotNull(CheckIn::parse) } catch (e: Exception) { emptyList() }

    fun append(context: Context, row: CheckIn) {
        val f = logFile(context)
        if (!f.exists()) f.writeText(CheckIn.HEADER + "\n")
        f.appendText(row.toCsv() + "\n")
    }

    fun hasPosterior(context: Context) = posteriorFile(context).exists()

    /**
     * Guarda la población y los tramos de la preconfiguración. Escribe a un temporal y lo renombra: si el proceso
     * muere a mitad, queda el archivo anterior entero.
     */
    fun savePosterior(context: Context, tuner: Evolver) {
        File(dir(context), "posterior.bin").delete() // la grilla de versiones anteriores (varios MB) ya no se usa
        val f = posteriorFile(context)
        val tmp = File(f.path + ".tmp")
        java.io.FileOutputStream(tmp).use { fos ->
            val out = fos.buffered()
            tuner.write(out)
            out.flush()
            fos.fd.sync() // a disco antes de renombrar: un corte de energía no deja un archivo vacío
        }
        if (!tmp.renameTo(f)) tmp.delete()
    }

    /** La evidencia ya no corresponde a la calibración (se importaron parámetros o falló guardarla): se descarta. */
    fun forgetPosterior(context: Context) {
        posteriorFile(context).delete()
    }

    private fun loadPosterior(context: Context): Evolver? =
        try { posteriorFile(context).inputStream().buffered().use { Evolver.read(it) } } catch (e: Exception) { null }

    /**
     * Suma la alarma a los posteriores de la población (peso [Tuner.ALARM_EVIDENCE]), cría una generación y cambia
     * la detección solo si otro individuo es claramente mejor. Devuelve qué pasó, para mostrarlo y guardarlo en el registro.
     */
    fun learn(context: Context, seg: Segment, real: Int, counted: Int, firstSquatMs: Long): String {
        val prefs = AlarmPrefs(context)
        val cal = prefs.calibration
        val tuner = if (cal.calibrated) loadPosterior(context) else null
        if (tuner == null || !Evolver.sameGenes(tuner.pop[0].cal, cal)) return "sin aprender: falta preconfigurar con esta versión"
        val from = Tuner.alarmKeepFrom(seg.startMs + seg.durationMs, firstSquatMs)
        if (firstSquatMs > 0L && from > firstSquatMs - Tuner.BEFORE_FIRST_MS) return "sin aprender: las sentadillas duraron más de lo grabado"
        seg.dropBefore(from)
        if (seg.durationMs < 10_000L) return "sin aprender: no hay datos de sensores"
        seg.truth = real
        seg.liveCount = counted
        val w = prefs.calWeight.toDouble()
        val new = tuner.learnFromAlarm(seg, w)
        savePosterior(context, tuner)
        prefs.learnedAlarms = prefs.learnedAlarms + 1
        val i = tuner.pop[0]
        val post = tuner.posterior(i)
        // La θ nueva ya refleja cómo detectas: la exigencia vuelve a ×1 (si no, se aplicaría dos veces).
        if (new != null) prefs.strictness = 1f
        // Si cambia θ, la validación anterior ya no corresponde: queda «sin validar».
        prefs.calibration = i.cal.copy(
            calibrated = true, nSquats = cal.nSquats, sens = post.sens.toFloat(), spec = post.spec.toFloat(),
            valSens = if (new == null) cal.valSens else -1f, valSpec = if (new == null) cal.valSpec else -1f,
        )
        return if (new == null) "aprendió; detección sin cambios" else "cambió la detección (claramente mejor); exigencia ×1"
    }
}
