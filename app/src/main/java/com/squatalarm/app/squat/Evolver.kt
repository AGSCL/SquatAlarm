package com.squatalarm.app.squat

/**
 * Aprendizaje genético sobre el bayesiano. La grilla de [BayesTuner] busca en valores fijos; aquí una población de
 * detectores con parámetros CONTINUOS (los genes) evoluciona por selección, cruce y mutación, partiendo de la mejor θ
 * de la grilla. La aptitud de cada individuo es la misma utilidad bayesiana: w·E[sens] + (1−w)·E[esp] con sus
 * posteriores Beta(1,1) + conteos.
 *
 * Se guardan los tramos grabados —la preconfiguración entera y las alarmas recientes— y todo individuo se evalúa con
 * ellos de forma exacta: las falsaciones son la única evidencia de trampas y no deben perderse al mutar. (Heredar de
 * los padres los conteos con descuento hacía ganar a hijos que no eran mejores; lo detectó core-check.)
 */
class Evolver(val pre: List<Segment>, val windowMs: Long, seed: Long = 1L) {

    /** Conteos ponderados [VP, FN, FP, VN]: preconfiguración (peso 1) + alarmas guardadas (peso [Tuner.ALARM_EVIDENCE]). */
    class Ind(val cal: Calibration, val c: FloatArray)

    /** Alarmas recientes, de la más antigua a la más nueva (como mucho [ALARM_SAMPLES] muestras en total). */
    val alarms = ArrayList<Segment>()

    /** Población; [0] es el individuo que usa el detector. */
    var pop = ArrayList<Ind>()
        private set
    private val rnd = java.util.Random(seed)

    fun posterior(i: Ind) = Posterior(1.0 + i.c[0], 1.0 + i.c[1], 1.0 + i.c[3], 1.0 + i.c[2])

    fun utility(i: Ind, w: Double): Double = posterior(i).let { w * it.sens + (1 - w) * it.spec }

    private fun born(cal: Calibration): Ind {
        val c = FloatArray(4)
        for (s in pre) addCounts(c, s, cal, 1f, Int.MAX_VALUE)
        // Los negativos de una alarma (el rato acostado), como mucho los de una falsación.
        val cap = maxOf(1, (Tuner.FALSIFICATION_MS / windowMs).toInt())
        for (s in alarms) addCounts(c, s, cal, Tuner.ALARM_EVIDENCE, cap)
        return Ind(cal, c)
    }

    private fun addCounts(acc: FloatArray, seg: Segment, cal: Calibration, evidence: Float, negCap: Int) {
        val neg = if (seg.negWindows >= 0) seg.negWindows else Tuner.negativeWindows(seg, windowMs)
        val negEv = evidence * if (neg > negCap) negCap.toFloat() / neg else 1f
        val d = Tuner.replay(seg, cal)
        val fp = maxOf(d - seg.truth, 0)
        acc[0] += evidence * minOf(d, seg.truth)
        acc[1] += evidence * maxOf(seg.truth - d, 0)
        acc[2] += negEv * fp
        acc[3] += negEv * maxOf(neg - fp, 0)
    }

    /** Selección por torneo: el mejor de tres al azar. */
    private fun pick(w: Double): Ind = (1..3).map { pop[rnd.nextInt(pop.size)] }.maxByOrNull { utility(it, w) }!!

    /**
     * Cruce uniforme + mutación gaussiana. Un veto apagado (herencia de la grilla) puede encenderse, pero ninguno se
     * apaga: en la simulación, un hijo sin veto «acostado» ganaba por otra mejora y la trampa volvía a pasar.
     */
    private fun child(a: Ind, b: Ind): Ind {
        val ga = genes(a.cal)
        val gb = genes(b.cal)
        val g = FloatArray(GENES) { if (rnd.nextBoolean()) ga[it] else gb[it] }
        for (k in 0 until GENES) {
            if (rnd.nextFloat() >= MUTATION) continue
            g[k] = if (g[k] == NONE[k]) LO[k] + rnd.nextFloat() * (HI[k] - LO[k])
            else (g[k] + rnd.nextGaussian().toFloat() * SD[k]).coerceIn(LO[k], HI[k])
        }
        // Como el control de «exigencia»: mueve los dos umbrales juntos. Sin esto, sentadillas más suaves que todas las
        // de la preconfiguración quedan tras un escalón que ninguna mutación de un solo gen cruza.
        if (rnd.nextFloat() < MUTATION) {
            val f = Math.exp(rnd.nextGaussian() * 0.25).toFloat()
            for (k in 0..1) g[k] = (g[k] * f).coerceIn(LO[k], HI[k])
        }
        return born(calibration(g))
    }

    /** [generations] generaciones con elitismo: la mejor utilidad nunca baja. Al final, [0] = el elegido, centrado. */
    fun evolve(w: Double, generations: Int = GENERATIONS) {
        repeat(generations) {
            val next = ArrayList(pop.sortedByDescending { utility(it, w) }.take(ELITE))
            while (next.size < POP) next.add(child(pick(w), pick(w)))
            pop = next
        }
        val best = best(w)
        pop.remove(best)
        pop.add(0, centered(best, w))
    }

    fun best(w: Double): Ind = pop.maxByOrNull { utility(it, w) }!!

    /**
     * Centra cada gen en su meseta: el tramo de valores que da la misma utilidad con los demás genes fijos. Los conteos
     * no distinguen un corte pegado a la trampa observada (61° si se hizo a 62°) de uno con margen; con datos nuevos sí
     * importa: la simulación de usuarios (core-check/sim-body.kts) mostró que sin esto la trampa acostado pasaba igual.
     * Bisección: supone que la meseta es un intervalo.
     */
    private fun centered(ind: Ind, w: Double): Ind {
        val u0 = utility(ind, w)
        val g = genes(ind.cal)
        for (k in 0 until GENES) {
            fun ok(v: Float): Boolean {
                val t = g.copyOf(); t[k] = v
                return utility(born(calibration(t)), w) >= u0 - 1e-9
            }
            val start = g[k].coerceIn(LO[k], HI[k])
            if (!ok(start)) continue
            fun edge(bound: Float): Float {
                if (ok(bound)) return bound
                var a = start
                var b = bound
                repeat(6) { val m = (a + b) / 2f; if (ok(m)) a = m else b = m }
                return a
            }
            val mid = (edge(LO[k]) + edge(HI[k])) / 2f
            if (ok(mid)) g[k] = mid // si la meseta no era un intervalo, el gen queda como estaba
        }
        return born(calibration(g))
    }

    /**
     * Suma una alarma a la evidencia (olvidando las más antiguas si no caben), cría una generación y devuelve el
     * individuo nuevo solo si es claramente mejor que el que usa el detector.
     */
    fun learnFromAlarm(seg: Segment, w: Double): Ind? {
        seg.negWindows = Tuner.negativeWindows(seg, windowMs)
        alarms.add(seg)
        while (alarms.size > 1 && alarms.sumOf { it.size } > ALARM_SAMPLES) alarms.removeAt(0)
        pop = ArrayList(pop.map { born(it.cal) }) // toda la población, con la evidencia al día
        val cur = pop[0]
        repeat(KIDS) { pop.add(child(pick(w), pick(w))) }
        pop = ArrayList(listOf(cur) + pop.drop(1).sortedByDescending { utility(it, w) }.take(POP - 1))
        val best = best(w)
        if (best === cur || !Tuner.clearlyBetter(posterior(best), posterior(cur), w)) return null
        pop.remove(best)
        pop.add(0, best)
        return best
    }

    fun write(out: java.io.OutputStream) {
        val d = java.io.DataOutputStream(out)
        d.writeInt(FORMAT)
        d.writeLong(windowMs)
        d.writeInt(pre.size)
        for (s in pre) s.write(d)
        d.writeInt(alarms.size)
        for (s in alarms) s.write(d)
        d.writeInt(pop.size)
        for (i in pop) for (g in genes(i.cal)) d.writeFloat(g)
        d.flush()
    }

    companion object {
        const val POP = 40
        const val GENERATIONS = 25
        private const val ELITE = 4
        private const val KIDS = 10
        private const val MUTATION = 0.3f
        /** Tope de muestras de alarmas guardadas (~50 min a 50 Hz, unos 4 MB): con más, se olvidan las más antiguas. */
        const val ALARM_SAMPLES = 150_000
        private const val FORMAT = 1

        // Genes: bajada, subida, ciclo mínimo, pico máximo, inclinación mínima y máxima, horizontalidad máxima.
        private const val GENES = 7
        private val LO = floatArrayOf(0.4f, 0.4f, 250f, 3f, 0f, 25f, 20f)
        private val HI = floatArrayOf(3f, 3f, 600f, 12f, 25f, 120f, 85f)
        private val SD = floatArrayOf(0.15f, 0.15f, 40f, 0.8f, 3f, 8f, 6f)
        /** Valor «sin veto» de cada gen (NaN = no es un veto). */
        private val NONE = floatArrayOf(Float.NaN, Float.NaN, Float.NaN, SquatCounter.NO_LIMIT, 0f, 180f, SquatCounter.NO_FLAT_LIMIT)

        private fun genes(c: Calibration) =
            floatArrayOf(c.thresholdDown, c.thresholdUp, c.minCycleMs.toFloat(), c.maxPeak, c.tiltMinDeg, c.tiltMaxDeg, c.flatMaxDeg)

        private fun calibration(g: FloatArray) = Calibration(g[0], g[1], g[2].toLong(), BayesTuner.MAX_CYCLE_MS, g[3], g[4], g[5], g[6])

        /** ¿Mismos parámetros de detección? (ignora las métricas guardadas junto a ellos). */
        fun sameGenes(a: Calibration, b: Calibration) = genes(a).contentEquals(genes(b))

        /** Población inicial: la mejor θ de la grilla bayesiana y mutantes suyos; después evoluciona. */
        fun fromGrid(tuner: BayesTuner, w: Double, seed: Long = 1L, generations: Int = GENERATIONS): Evolver {
            val e = Evolver(tuner.segments.toList(), if (tuner.windowMs > 0L) tuner.windowMs else BayesTuner.DEFAULT_WINDOW_MS, seed)
            val first = e.born(calibration(genes(tuner.best(w).first)))
            e.pop.add(first)
            while (e.pop.size < POP) e.pop.add(e.child(first, first))
            e.evolve(w, generations)
            return e
        }

        /** Lee lo guardado con [write]; null si es de otra versión. */
        fun read(input: java.io.InputStream, seed: Long = System.nanoTime()): Evolver? {
            val d = java.io.DataInputStream(input)
            if (d.readInt() != FORMAT) return null
            val window = d.readLong()
            val e = Evolver(List(d.readInt()) { Segment.read(d) }, window, seed)
            repeat(d.readInt()) { e.alarms.add(Segment.read(d)) }
            repeat(d.readInt()) { e.pop.add(e.born(calibration(FloatArray(GENES) { d.readFloat() }))) }
            return e.takeIf { it.pop.isNotEmpty() }
        }
    }
}
