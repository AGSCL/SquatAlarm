package com.squatalarm.app.squat

/**
 * Parámetros de detección (m/s², ms, grados) y las métricas con que se obtuvieron
 * (medias posteriores de sensibilidad/especificidad; -1 = no medido).
 */
data class Calibration(
    val thresholdDown: Float,
    val thresholdUp: Float,
    val minCycleMs: Long,
    val maxCycleMs: Long,
    val maxPeak: Float = SquatCounter.NO_LIMIT,
    val tiltMinDeg: Float = 0f,
    val tiltMaxDeg: Float = 180f,
    val calibrated: Boolean = false,
    val nSquats: Int = 0,
    val sens: Float = -1f,
    val spec: Float = -1f,
    val valSens: Float = -1f,
    val valSpec: Float = -1f,
) {
    /** Contador con estos parámetros; [strictness] multiplica los umbrales de bajada y subida. */
    fun counter(strictness: Float = 1f) = SquatCounter(
        thresholdDown * strictness, thresholdUp * strictness, minCycleMs, maxCycleMs, maxPeak, tiltMinDeg, tiltMaxDeg,
    )

    /** Aplica estos parámetros a un contador existente (detector en vivo). */
    fun applyTo(c: SquatCounter) {
        c.thresholdDown = thresholdDown
        c.thresholdUp = thresholdUp
        c.minCycleMs = minCycleMs
        c.maxCycleMs = maxCycleMs
        c.maxPeak = maxPeak
        c.tiltMinDeg = tiltMinDeg
        c.tiltMaxDeg = tiltMaxDeg
    }

    companion object {
        val DEFAULT = Calibration(1.2f, 1.2f, 350L, 4000L)
    }
}
