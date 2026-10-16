package com.squatalarm.app.alarm

import kotlin.math.roundToInt

/**
 * Inicio suave, para no despertar a otros: [SOFT_MS] de vibración y sonido bajo, [SILENT_MS] de silencio
 * y después la alarma completa. Kotlin puro (se prueba en core-check).
 */
object RingPlan {
    enum class Phase { SOFT, SILENT, LOUD }

    const val SOFT_MS = 90_000L
    const val SILENT_MS = 120_000L

    /** Fase según el tiempo desde que empezó a sonar (sobrevive a reinicios: se calcula desde ringing_since). */
    fun phaseAt(elapsedMs: Long, softStart: Boolean): Phase = when {
        !softStart || elapsedMs >= SOFT_MS + SILENT_MS -> Phase.LOUD
        elapsedMs >= SOFT_MS -> Phase.SILENT
        else -> Phase.SOFT
    }

    /** Volumen elegido (fracción del máximo) → paso del volumen de alarma; nunca 0: la alarma no puede quedar muda. */
    fun volumeIndex(fraction: Float, max: Int): Int = (fraction * max).roundToInt().coerceIn(1, max)
}
