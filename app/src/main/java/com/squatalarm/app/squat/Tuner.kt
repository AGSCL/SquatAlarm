package com.squatalarm.app.squat

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/** Un tramo grabado en la preconfiguración: señal + orientación + lo que el usuario dijo que hizo. */
class Segment(val name: String, val positive: Boolean) {
    private val a = ArrayList<Float>()
    private val t = ArrayList<Long>()
    private val gx = ArrayList<Float>()
    private val gy = ArrayList<Float>()
    private val gz = ArrayList<Float>()

    /** Sentadillas reales según el usuario. */
    var truth = 0
    /** Lo que contó la app en vivo mientras se grababa. */
    var liveCount = 0
    /** Ventanas sin sentadilla de este tramo (se fija al analizarlo; -1 = aún no). */
    var negWindows = -1

    val size: Int get() = a.size
    val durationMs: Long get() = if (t.size < 2) 0L else t[t.size - 1] - t[0]

    fun add(aRaw: Float, time: Long, ux: Float, uy: Float, uz: Float) {
        a.add(aRaw); t.add(time); gx.add(ux); gy.add(uy); gz.add(uz)
    }

    // Copias primitivas para el replay (miles de pasadas): se rehacen solo si el tramo creció.
    private var cached = -1
    private var fa = FloatArray(0)
    private var ft = LongArray(0)
    private var fx = FloatArray(0)
    private var fy = FloatArray(0)
    private var fz = FloatArray(0)

    /** Pasa todo el tramo por [counter] (reiniciado) y entrega cada ciclo terminado. */
    inline fun replay(counter: SquatCounter, onCycle: (SquatCounter.Squat) -> Unit) {
        counter.reset()
        val n = prepareArrays()
        for (i in 0 until n) {
            val s = processAt(counter, i)
            if (s != null) onCycle(s)
        }
    }

    fun prepareArrays(): Int {
        if (cached != a.size) {
            fa = a.toFloatArray(); ft = t.toLongArray(); fx = gx.toFloatArray(); fy = gy.toFloatArray(); fz = gz.toFloatArray()
            cached = a.size
        }
        return cached
    }

    fun processAt(counter: SquatCounter, i: Int): SquatCounter.Squat? = counter.process(fa[i], ft[i], fx[i], fy[i], fz[i])
}

/** Matriz de confusión por ventanas (ver [Tuner.negativeWindows]). */
class Counts(val tp: Int, val fn: Int, val fp: Int, val tn: Int) {
    val nPos: Int get() = tp + fn
    val nNeg: Int get() = fp + tn
    operator fun plus(o: Counts) = Counts(tp + o.tp, fn + o.fn, fp + o.fp, tn + o.tn)

    /** Posterior con previa uniforme Beta(1,1) para sensibilidad y especificidad. */
    fun posterior() = Posterior(1.0 + tp, 1.0 + fn, 1.0 + tn, 1.0 + fp)

    companion object {
        val ZERO = Counts(0, 0, 0, 0)
    }
}

/** Sensibilidad ~ Beta(aS, bS), especificidad ~ Beta(aE, bE). */
class Posterior(val aS: Double, val bS: Double, val aE: Double, val bE: Double) {
    val sens: Double get() = aS / (aS + bS)
    val spec: Double get() = aE / (aE + bE)
    /** Hay datos negativos (si no, la especificidad es solo la previa). */
    val hasNeg: Boolean get() = aE + bE > 2.0
    fun sensCrI(): Pair<Double, Double> = BetaDist.quantile(0.025, aS, bS) to BetaDist.quantile(0.975, aS, bS)
    fun specCrI(): Pair<Double, Double> = BetaDist.quantile(0.025, aE, bE) to BetaDist.quantile(0.975, aE, bE)
    fun probSensAtLeast(x: Double): Double = 1.0 - BetaDist.cdf(x, aS, bS)
    fun probSpecAtLeast(x: Double): Double = 1.0 - BetaDist.cdf(x, aE, bE)
}

/** Distribución Beta: CDF (beta incompleta regularizada) y cuantiles exactos. */
object BetaDist {
    fun cdf(x: Double, a: Double, b: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val front = exp(lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * ln(x) + b * ln(1.0 - x))
        return if (x < (a + 1.0) / (a + b + 2.0)) front * betacf(x, a, b) / a
        else 1.0 - front * betacf(1.0 - x, b, a) / b
    }

    /** Cuantil por bisección (60 iteraciones ≈ precisión de máquina). */
    fun quantile(p: Double, a: Double, b: Double): Double {
        var lo = 0.0
        var hi = 1.0
        repeat(60) {
            val mid = (lo + hi) / 2.0
            if (cdf(mid, a, b) < p) lo = mid else hi = mid
        }
        return (lo + hi) / 2.0
    }

    /** Fracción continua de la beta incompleta (Lentz; Numerical Recipes 6.4). */
    private fun betacf(x: Double, a: Double, b: Double): Double {
        val tiny = 1e-30
        val qab = a + b
        val qap = a + 1.0
        val qam = a - 1.0
        var c = 1.0
        var d = 1.0 - qab * x / qap
        if (abs(d) < tiny) d = tiny
        d = 1.0 / d
        var h = d
        for (m in 1..300) {
            val m2 = 2.0 * m
            var aa = m * (b - m) * x / ((qam + m2) * (a + m2))
            d = 1.0 + aa * d; if (abs(d) < tiny) d = tiny
            c = 1.0 + aa / c; if (abs(c) < tiny) c = tiny
            d = 1.0 / d
            h *= d * c
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
            d = 1.0 + aa * d; if (abs(d) < tiny) d = tiny
            c = 1.0 + aa / c; if (abs(c) < tiny) c = tiny
            d = 1.0 / d
            val del = d * c
            h *= del
            if (abs(del - 1.0) < 1e-13) break
        }
        return h
    }

    /** log Γ(x) por Lanczos (g = 7). */
    fun lnGamma(x: Double): Double {
        if (x < 0.5) return ln(PI / sin(PI * x)) - lnGamma(1.0 - x)
        val xx = x - 1.0
        var s = LANCZOS[0]
        for (i in 1 until LANCZOS.size) s += LANCZOS[i] / (xx + i)
        val t = xx + 7.5
        return 0.5 * ln(2.0 * PI) + (xx + 0.5) * ln(t) - t + ln(s)
    }

    private val LANCZOS = doubleArrayOf(
        0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
        -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6,
        1.5056327351493116e-7,
    )
}

/**
 * Adaptación bayesiana de la detección. Cada combinación θ de parámetros (umbrales de bajada y
 * subida, tiempo mínimo, pico máximo, ventana de inclinación) tiene su propio posterior
 * Beta-binomial de sensibilidad y especificidad, con previa uniforme Beta(1,1). Tras cada paso de la
 * preconfiguración se re-analiza solo ese tramo con todas las θ (conjugación: los conteos se suman)
 * y el detector pasa a la θ que maximiza la utilidad esperada a posteriori
 * w·E[sens] + (1−w)·E[esp]. Empates: veto menos restrictivo y centro de la meseta de umbrales.
 */
class BayesTuner {
    /** Largo de la ventana: duración / sentadillas reales de la primera serie (0 = aún no hay serie). */
    var windowMs = 0L
        private set
    val segments = ArrayList<Segment>()
    /** Traza de aprendizaje: la θ elegida y su posterior tras cada paso. */
    val history = ArrayList<Snapshot>()

    class Snapshot(val step: String, val calibration: Calibration, val posterior: Posterior)

    private val tp = IntArray(N)
    private val fn = IntArray(N)
    private val fp = IntArray(N)
    private val tn = IntArray(N)

    /** Incorpora un tramo con su verdad declarada y actualiza todos los posteriores. */
    fun add(seg: Segment, weight: Double) {
        if (windowMs == 0L && seg.positive && seg.truth > 0) {
            windowMs = (seg.durationMs / seg.truth).coerceIn(1000L, 5000L)
        }
        seg.negWindows = Tuner.negativeWindows(seg, if (windowMs > 0L) windowMs else DEFAULT_WINDOW_MS)
        val truth = seg.truth
        var negAbs = FloatArray(64)
        var pos = FloatArray(64)
        var tilt = FloatArray(64)
        for (b in 0 until N_BASE) {
            var k = 0
            seg.replay(baseCounter(b)) { s ->
                if (k == negAbs.size) {
                    negAbs = negAbs.copyOf(k * 2); pos = pos.copyOf(k * 2); tilt = tilt.copyOf(k * 2)
                }
                negAbs[k] = -s.negPeak; pos[k] = s.posPeak; tilt[k] = s.tiltDeg
                k++
            }
            for (v in 0 until N_VETO) {
                val maxPeak = PEAKS[v / (N_TMIN * N_TMAX)]
                val tMin = TILT_MINS[(v / N_TMAX) % N_TMIN]
                val tMax = TILT_MAXS[v % N_TMAX]
                var d = 0
                for (j in 0 until k) {
                    if (SquatCounter.rejectReason(negAbs[j], pos[j], tilt[j], maxPeak, tMin, tMax) == SquatCounter.Reject.NONE) d++
                }
                val i = b * N_VETO + v
                val fpSeg = maxOf(d - truth, 0)
                tp[i] += minOf(d, truth)
                fn[i] += maxOf(truth - d, 0)
                fp[i] += fpSeg
                tn[i] += maxOf(seg.negWindows - fpSeg, 0)
            }
        }
        segments.add(seg)
        val i = bestIndex(weight)
        history.add(Snapshot(seg.name, calibration(i), counts(i).posterior()))
    }

    fun counts(i: Int) = Counts(tp[i], fn[i], fp[i], tn[i])

    /** Mejor θ a posteriori para el peso [weight] de la sensibilidad (instantáneo: no re-analiza). */
    fun best(weight: Double): Pair<Calibration, Posterior> {
        val i = bestIndex(weight)
        val post = counts(i).posterior()
        val nSquats = segments.filter { it.positive }.sumOf { it.truth }
        return calibration(i).copy(calibrated = true, nSquats = nSquats, sens = post.sens.toFloat(), spec = post.spec.toFloat()) to post
    }

    fun bestIndex(weight: Double): Int {
        var maxU = -1.0
        for (i in 0 until N) {
            val u = utility(i, weight)
            if (u > maxU) maxU = u
        }
        val tied = (0 until N).filter { utility(it, weight) >= maxU - 1e-9 }
        val minScore = tied.minOf { vetoScore(it % N_VETO) }
        val cand = tied.filter { vetoScore(it % N_VETO) == minScore }
        val mDown = median(cand.map { iDown(it) })
        val mUp = median(cand.map { iUp(it) })
        val mCyc = median(cand.map { iCycle(it) })
        return cand.minByOrNull { abs(iDown(it) - mDown) + abs(iUp(it) - mUp) + abs(iCycle(it) - mCyc) }!!
    }

    private fun utility(i: Int, w: Double): Double =
        w * (1.0 + tp[i]) / (2.0 + tp[i] + fn[i]) + (1.0 - w) * (1.0 + tn[i]) / (2.0 + tn[i] + fp[i])

    companion object {
        val THRESHOLDS: List<Float> = (4..30).map { it / 10f }
        val MIN_CYCLES = longArrayOf(300L, 400L, 500L)
        const val MAX_CYCLE_MS = 5000L
        /** Vetos, del menos al más restrictivo. */
        val PEAKS = floatArrayOf(SquatCounter.NO_LIMIT, 12f, 9f, 7f, 5.5f, 4.5f, 3.5f)
        val TILT_MINS = floatArrayOf(0f, 5f, 10f, 20f)
        val TILT_MAXS = floatArrayOf(180f, 90f, 60f, 45f, 30f)
        const val DEFAULT_WINDOW_MS = 2500L

        private val N_T = THRESHOLDS.size
        private val N_CYC = MIN_CYCLES.size
        private val N_TMIN = TILT_MINS.size
        private val N_TMAX = TILT_MAXS.size
        val N_BASE = N_T * N_T * N_CYC
        val N_VETO = PEAKS.size * N_TMIN * N_TMAX
        val N = N_BASE * N_VETO

        private fun iDown(i: Int) = (i / N_VETO) / (N_T * N_CYC)
        private fun iUp(i: Int) = ((i / N_VETO) / N_CYC) % N_T
        private fun iCycle(i: Int) = (i / N_VETO) % N_CYC
        private fun vetoScore(v: Int) = v / (N_TMIN * N_TMAX) + (v / N_TMAX) % N_TMIN + v % N_TMAX

        fun calibration(i: Int): Calibration {
            val v = i % N_VETO
            return Calibration(
                thresholdDown = THRESHOLDS[iDown(i)],
                thresholdUp = THRESHOLDS[iUp(i)],
                minCycleMs = MIN_CYCLES[iCycle(i)],
                maxCycleMs = MAX_CYCLE_MS,
                maxPeak = PEAKS[v / (N_TMIN * N_TMAX)],
                tiltMinDeg = TILT_MINS[(v / N_TMAX) % N_TMIN],
                tiltMaxDeg = TILT_MAXS[v % N_TMAX],
            )
        }

        private fun baseCounter(b: Int) = SquatCounter(
            THRESHOLDS[b / (N_T * N_CYC)], THRESHOLDS[(b / N_CYC) % N_T], MIN_CYCLES[b % N_CYC], MAX_CYCLE_MS,
        )

        private fun median(values: List<Int>): Double {
            val s = values.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2].toDouble() else (s[n / 2 - 1] + s[n / 2]) / 2.0
        }
    }
}

object Tuner {
    /** Umbrales permisivos para contar en vivo antes de tener datos. */
    val PERMISSIVE = Calibration(0.45f, 0.45f, 250L, 5000L)
    const val VALIDATION_MIN_SENS = 0.8
    const val VALIDATION_MIN_SPEC = 0.9

    /**
     * Ventanas sin sentadilla: el tramo se divide en ventanas del largo de una sentadilla del usuario;
     * `truth` de ellas son positivas y el resto negativas.
     */
    fun negativeWindows(seg: Segment, windowMs: Long): Int =
        maxOf(seg.truth, (seg.durationMs / windowMs).toInt(), 1) - seg.truth

    /** Cuántas sentadillas cuenta [c] en el tramo. */
    fun replay(seg: Segment, c: Calibration): Int {
        val counter = c.counter()
        seg.replay(counter) { }
        return counter.count
    }

    /** Matriz de confusión de [c] sobre [segments]. */
    fun evaluate(segments: List<Segment>, c: Calibration, windowMs: Long): Counts {
        var total = Counts.ZERO
        for (seg in segments) {
            val d = replay(seg, c)
            val neg = if (seg.negWindows >= 0) seg.negWindows
            else negativeWindows(seg, if (windowMs > 0L) windowMs else BayesTuner.DEFAULT_WINDOW_MS)
            val fpSeg = maxOf(d - seg.truth, 0)
            total += Counts(minOf(d, seg.truth), maxOf(seg.truth - d, 0), fpSeg, maxOf(neg - fpSeg, 0))
        }
        return total
    }
}
