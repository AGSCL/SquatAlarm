package com.squatalarm.app.squat

import kotlin.math.abs

/** Un tramo grabado en la preconfiguración: señal vertical sin filtrar + lo que el usuario dijo que hizo. */
class Segment(val name: String, val positive: Boolean) {
    val a = ArrayList<Float>()
    val t = ArrayList<Long>()
    /** Sentadillas reales según el usuario. */
    var truth = 0
    /** Lo que contó la app en vivo mientras se grababa. */
    var liveCount = 0

    val durationMs: Long get() = if (t.size < 2) 0L else t[t.size - 1] - t[0]

    fun add(aRaw: Float, time: Long) {
        a.add(aRaw); t.add(time)
    }
}

/**
 * Métricas por ventanas: cada tramo se divide en ventanas del largo de una sentadilla del usuario;
 * `truth` de ellas son positivas (sentadillas reales) y el resto negativas (no-sentadilla).
 * Sensibilidad = reales detectadas / reales. Especificidad = ventanas negativas sin falsa detección / negativas.
 */
class Metrics(val tp: Int, val fn: Int, val fp: Int, val tn: Int) {
    val nPos: Int get() = tp + fn
    val nNeg: Int get() = fp + tn
    val sens: Float get() = if (nPos == 0) 1f else tp / nPos.toFloat()
    val spec: Float get() = if (nNeg == 0) 1f else tn / nNeg.toFloat()
    fun score(weight: Float): Float = weight * sens + (1f - weight) * spec
}

object Tuner {
    /** Umbrales permisivos para contar en vivo mientras se graba la preconfiguración. */
    val PERMISSIVE = Calibration(0.45f, 0.45f, 250L, 5000L)
    const val VALIDATION_MIN_SENS = 0.8f
    const val VALIDATION_MIN_SPEC = 0.9f

    private class Prepared(val a: FloatArray, val t: LongArray, val truth: Int, val durationMs: Long)

    /** Largo de una ventana: tiempo total de las series / sentadillas reales declaradas. */
    fun windowMs(segments: List<Segment>): Long {
        val pos = segments.filter { it.positive && it.truth > 0 }
        if (pos.isEmpty()) return 2000L
        return (pos.sumOf { it.durationMs } / pos.sumOf { it.truth }).coerceIn(1000L, 5000L)
    }

    /** Re-analiza un tramo con otros umbrales. */
    fun replay(seg: Segment, c: Calibration): Int = count(prepare(seg), c)

    fun evaluate(segments: List<Segment>, c: Calibration): Metrics =
        evaluate(segments.map(::prepare), c, windowMs(segments))

    /**
     * Búsqueda en rejilla de (umbral bajada, umbral subida, tiempo mínimo) que maximiza
     * weight·sens + (1−weight)·esp. Entre empates elige el centro de la meseta (más robusto).
     */
    fun tune(segments: List<Segment>, weight: Float): Pair<Calibration, Metrics> {
        val prepared = segments.map(::prepare)
        val window = windowMs(segments)
        val maxCycle = (window * 1.5f).toLong().coerceIn(2500L, 6000L)
        val best = ArrayList<Pair<Calibration, Metrics>>()
        var bestScore = -1f
        for (down in THRESHOLDS) for (up in THRESHOLDS) for (minCycle in MIN_CYCLES) {
            val c = Calibration(down, up, minCycle, maxCycle)
            val m = evaluate(prepared, c, window)
            val s = m.score(weight)
            if (s > bestScore + 1e-6f) {
                bestScore = s
                best.clear()
            }
            if (s > bestScore - 1e-6f) best.add(c to m)
        }
        val medDown = median(best.map { it.first.thresholdDown })
        val medUp = median(best.map { it.first.thresholdUp })
        val medCycle = median(best.map { it.first.minCycleMs.toFloat() })
        val (c, m) = best.minByOrNull {
            abs(it.first.thresholdDown - medDown) + abs(it.first.thresholdUp - medUp) +
                abs(it.first.minCycleMs - medCycle) / 1000f
        }!!
        val nSquats = segments.filter { it.positive }.sumOf { it.truth }
        return c.copy(calibrated = true, nSquats = nSquats, sens = m.sens, spec = m.spec) to m
    }

    private fun prepare(s: Segment) = Prepared(s.a.toFloatArray(), s.t.toLongArray(), s.truth, s.durationMs)

    private fun count(p: Prepared, c: Calibration): Int {
        val k = SquatCounter(c.thresholdDown, c.thresholdUp, c.minCycleMs, c.maxCycleMs)
        for (i in p.a.indices) k.process(p.a[i], p.t[i])
        return k.count
    }

    private fun evaluate(prepared: List<Prepared>, c: Calibration, windowMs: Long): Metrics {
        var tp = 0; var fn = 0; var fp = 0; var tn = 0
        for (p in prepared) {
            val d = count(p, c)
            val windows = maxOf(p.truth, (p.durationMs / windowMs).toInt(), 1)
            val fpSeg = maxOf(d - p.truth, 0)
            tp += minOf(d, p.truth)
            fn += maxOf(p.truth - d, 0)
            fp += fpSeg
            tn += maxOf(windows - p.truth - fpSeg, 0)
        }
        return Metrics(tp, fn, fp, tn)
    }

    private fun median(values: List<Float>): Float {
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
    }

    private val THRESHOLDS: List<Float> = (4..30).map { it / 10f }
    private val MIN_CYCLES: List<Long> = listOf(300L, 400L, 500L)
}
