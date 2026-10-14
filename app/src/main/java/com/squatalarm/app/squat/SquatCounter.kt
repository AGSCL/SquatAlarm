package com.squatalarm.app.squat

import kotlin.math.acos

/**
 * Máquina de estados que cuenta sentadillas a partir de la aceleración vertical (m/s², + = arriba)
 * y de la orientación del teléfono (vector unitario de gravedad en el marco del teléfono).
 * Kotlin puro, sin Android: la misma clase se usa en vivo (SquatDetector) y en el replay (BayesTuner).
 *
 * Patrón de una sentadilla: lóbulo NEGATIVO (empieza a bajar) seguido de un lóbulo POSITIVO
 * (frena abajo y se impulsa). Cada ciclo candidato se acepta o se veta por:
 *  - pico máximo: los brazos mueven el teléfono mucho más bruscamente que el torso;
 *  - inclinación: con el teléfono contra el pecho, el torso se inclina al bajar; moviéndolo
 *    con los brazos la inclinación suele ser nula (teléfono vertical) o exagerada.
 * Un ciclo vetado pasa por los mismos estados que uno contado: así el replay con vetos
 * aplicados después es idéntico a contar en vivo con esos vetos.
 */
class SquatCounter(
    var thresholdDown: Float,
    var thresholdUp: Float,
    var minCycleMs: Long,
    var maxCycleMs: Long,
    var maxPeak: Float = NO_LIMIT,
    var tiltMinDeg: Float = 0f,
    var tiltMaxDeg: Float = 180f,
    var refractoryMs: Long = 700L,
) {
    enum class State { IDLE, DESCENDING, BOTTOM, REFRACTORY }

    enum class Reject { NONE, TOO_HARD, TOO_LITTLE_TILT, TOO_MUCH_TILT }

    /** Un ciclo candidato. negPeak < 0. Si [reject] != NONE no se contó. */
    class Squat(val negPeak: Float, val posPeak: Float, val cycleMs: Long, val tiltDeg: Float, val reject: Reject)

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
    private var g0x = 0f
    private var g0y = 0f
    private var g0z = 1f
    private var minCos = 1f

    fun reset() {
        count = 0
        state = State.IDLE
        filtered = 0f
        tPrev = 0L; tDown = 0L; tLastBelow = 0L; tLobeStart = 0L; tReady = 0L
        negPeak = 0f; posPeak = 0f
        minCos = 1f
    }

    /**
     * Procesa una muestra: [raw] aceleración vertical sin filtrar, [t] en ms y la gravedad como
     * vector UNITARIO (gx, gy, gz). Devuelve el ciclo recién terminado (contado o vetado) o null.
     */
    fun process(raw: Float, t: Long, gx: Float, gy: Float, gz: Float): Squat? {
        // Paso bajo (~2-3 Hz) independiente de la frecuencia de muestreo.
        val dt = if (tPrev == 0L) 20L else (t - tPrev).coerceIn(1L, 100L)
        tPrev = t
        filtered += (dt.toFloat() / (dt + FILTER_TAU_MS)) * (raw - filtered)
        val a = filtered

        if (state == State.DESCENDING || state == State.BOTTOM) {
            val c = gx * g0x + gy * g0y + gz * g0z
            if (c < minCos) minCos = c
        }

        when (state) {
            State.IDLE -> if (a < -thresholdDown) {
                state = State.DESCENDING
                tDown = t
                tLastBelow = t
                negPeak = a
                g0x = gx; g0y = gy; g0z = gz
                minCos = 1f
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
                    state = State.REFRACTORY
                    tReady = t + refractoryMs
                    val tilt = tiltDeg(minCos)
                    val reject = rejectReason(-negPeak, posPeak, tilt, maxPeak, tiltMinDeg, tiltMaxDeg)
                    if (reject == Reject.NONE) count++
                    return Squat(negPeak, posPeak, t - tDown, tilt, reject)
                }
            }
            State.REFRACTORY -> if (t >= tReady) state = State.IDLE
        }
        return null
    }

    companion object {
        /** "Sin límite" para el pico máximo. */
        const val NO_LIMIT = 1000f
        private const val FILTER_TAU_MS = 60f
        private const val LOBE_MAX_MS = 600L
        private const val NEW_LOBE_GAP_MS = 300L

        fun tiltDeg(minCos: Float): Float = Math.toDegrees(acos(minCos.coerceIn(-1f, 1f).toDouble())).toFloat()

        /** Única regla de veto: la usan el contador en vivo y el replay del BayesTuner. */
        fun rejectReason(negAbs: Float, posPeak: Float, tilt: Float, maxPeak: Float, tiltMin: Float, tiltMax: Float): Reject = when {
            negAbs > maxPeak || posPeak > maxPeak -> Reject.TOO_HARD
            tilt < tiltMin -> Reject.TOO_LITTLE_TILT
            tilt > tiltMax -> Reject.TOO_MUCH_TILT
            else -> Reject.NONE
        }
    }
}
