package com.squatalarm.app.squat

/**
 * Máquina de estados que cuenta sentadillas a partir de la aceleración vertical (m/s², + = arriba).
 * Kotlin puro, sin Android: la misma clase se usa en vivo (SquatDetector) y en el replay del Tuner.
 *
 * Patrón de una sentadilla: lóbulo NEGATIVO (empieza a bajar) seguido de un lóbulo POSITIVO
 * (frena abajo y se impulsa hacia arriba). Se cuenta cuando termina el lóbulo positivo.
 * Sacudidas rápidas se descartan porque el tiempo negativo→positivo es menor que [minCycleMs].
 */
class SquatCounter(
    var thresholdDown: Float,
    var thresholdUp: Float,
    var minCycleMs: Long,
    var maxCycleMs: Long,
    var refractoryMs: Long = 700L,
) {
    enum class State { IDLE, DESCENDING, BOTTOM, REFRACTORY }

    class Squat(val negPeak: Float, val posPeak: Float, val cycleMs: Long)

    var count = 0
        private set
    var state = State.IDLE
        private set
    /** Última aceleración filtrada (para la UI). */
    var filtered = 0f
        private set

    private var tPrev = 0L
    private var tDown = 0L
    private var tLastBelow = 0L
    private var tLobeStart = 0L
    private var tReady = 0L
    private var negPeak = 0f
    private var posPeak = 0f

    fun reset() {
        count = 0
        state = State.IDLE
        filtered = 0f
        tPrev = 0L; tDown = 0L; tLastBelow = 0L; tLobeStart = 0L; tReady = 0L
        negPeak = 0f; posPeak = 0f
    }

    /** Procesa una muestra ([raw] sin filtrar, [t] en ms). Devuelve la sentadilla recién contada o null. */
    fun process(raw: Float, t: Long): Squat? {
        // Paso bajo (~2-3 Hz) independiente de la frecuencia de muestreo.
        val dt = if (tPrev == 0L) 20L else (t - tPrev).coerceIn(1L, 100L)
        tPrev = t
        filtered += (dt.toFloat() / (dt + FILTER_TAU_MS)) * (raw - filtered)
        val a = filtered

        when (state) {
            State.IDLE -> if (a < -thresholdDown) {
                state = State.DESCENDING
                tDown = t
                tLastBelow = t
                negPeak = a
            }
            State.DESCENDING -> {
                if (a < -thresholdDown) {
                    // Lóbulo negativo nuevo (tras una pausa) reinicia el reloj; un re-cruce breve no.
                    if (t - tLastBelow > NEW_LOBE_GAP_MS) tDown = t
                    tLastBelow = t
                    if (a < negPeak) negPeak = a
                }
                if (t - tDown > maxCycleMs) {
                    state = State.IDLE
                } else if (a > thresholdUp) {
                    if (t - tDown < minCycleMs) {
                        state = State.IDLE // demasiado rápido: sacudida o ruido
                    } else {
                        state = State.BOTTOM
                        posPeak = a
                        tLobeStart = t
                    }
                }
            }
            State.BOTTOM -> {
                if (a > posPeak) posPeak = a
                if (a < thresholdUp * 0.5f || t - tLobeStart > LOBE_MAX_MS) {
                    count++
                    state = State.REFRACTORY
                    tReady = t + refractoryMs
                    return Squat(negPeak, posPeak, t - tDown)
                }
            }
            State.REFRACTORY -> if (t >= tReady) state = State.IDLE
        }
        return null
    }

    companion object {
        private const val FILTER_TAU_MS = 60f
        private const val LOBE_MAX_MS = 600L
        private const val NEW_LOBE_GAP_MS = 300L
    }
}
