package com.squatalarm.app.squat

/** Umbrales de detección (m/s² y ms) y las métricas con que se obtuvieron. -1 = no medido. */
data class Calibration(
    val thresholdDown: Float,
    val thresholdUp: Float,
    val minCycleMs: Long,
    val maxCycleMs: Long,
    val calibrated: Boolean = false,
    val nSquats: Int = 0,
    val sens: Float = -1f,
    val spec: Float = -1f,
    val valSens: Float = -1f,
    val valSpec: Float = -1f,
) {
    companion object {
        val DEFAULT = Calibration(1.2f, 1.2f, 350L, 4000L)
    }
}
