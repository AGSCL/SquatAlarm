
// ---------------------------------------------------------------------------------------------
// Simulación de usuarios: cada uno tiene su forma de hacer sentadillas, de sostener el teléfono y de
// hacer trampa acostado. Se preconfigura con sus datos y se mide con datos NUEVOS del mismo usuario.
// Los rangos de cada rasgo son supuestos (no mediciones): sirve para comparar estrategias de aprendizaje,
// no para predecir cifras reales.  Uso: core-check/run.sh sim-body.kts
// ---------------------------------------------------------------------------------------------
class Smp(val a: Float, val t: Long, val phi: Double) // phi = ángulo de la pantalla respecto de la vertical

class Gen(seed: Long) {
    val r = java.util.Random(seed)
    fun u(lo: Double, hi: Double) = lo + (hi - lo) * r.nextDouble()
    fun g(sd: Double) = r.nextGaussian() * sd
}

class User(val q: Gen) {
    val amp = q.u(1.6, 3.4)          // aceleración de la sentadilla, m/s²
    val period = q.u(1600.0, 3000.0) // ms
    val pause = q.u(500.0, 1700.0)
    val hold = q.u(3.0, 28.0)        // pantalla respecto de la vertical, de pie (pendiente del pecho + agarre)
    val lean = q.u(14.0, 42.0)       // inclinación del torso en el fondo
    val bed = q.u(0.0, 50.0)         // almohadas: 0 = plano; acostado la pantalla queda a 90 − bed
    val wobble = q.u(4.0, 30.0) * (if (q.r.nextBoolean()) 1 else -1) // giro del teléfono al estirar los brazos (− = hacia la vertical)
    val noise = q.u(0.08, 0.28)

    /** n ciclos bajada→subida con variación entre repeticiones y ruido de sensor. */
    fun motion(n: Int, period: Double, amp: Double, phi0: Double, dPhi: Double, pause: Double): List<Smp> {
        val out = ArrayList<Smp>()
        var t = 0L
        fun still(ms: Double, base: Double) { repeat((ms / 20).toInt()) { out.add(Smp(q.g(noise).toFloat(), t, base + q.g(1.0))); t += 20 } }
        val base0 = phi0 + q.g(2.0)
        still(1000.0, base0)
        repeat(n) {
            val p = period * (1 + q.g(0.08)).coerceIn(0.7, 1.3)
            val a = amp * (1 + q.g(0.12)).coerceIn(0.6, 1.5)
            val d = dPhi * (1 + q.g(0.15))
            val base = base0 + q.g(2.0)
            for (k in 0 until (p / 20).toInt()) {
                val ph = 2 * PI * k * 20 / p
                val s = sin(ph / 2)
                out.add(Smp((-a * sin(ph) + q.g(noise)).toFloat(), t, base + d * s * s + q.g(1.0))); t += 20
            }
            still(pause * (1 + q.g(0.2)).coerceIn(0.5, 1.6), base)
        }
        return out
    }

    fun periodic(seconds: Int, hz: Double, amp: Double, phi0: Double, dPhi: Double): List<Smp> =
        (0 until seconds * 1000 step 20).map { t ->
            val ph = 2 * PI * hz * t / 1000.0
            Smp((amp * sin(ph) + q.g(noise)).toFloat(), t.toLong(), phi0 + dPhi * abs(sin(ph)) + q.g(1.0))
        }

    fun squats(n: Int, k: Double = 1.0) = motion(n, period, amp * k, hold, lean * (0.5 + 0.5 * k), pause)
    fun reps20(period: Double, pause: Double) = (20_000 / (period + pause)).toInt().coerceAtLeast(2)
    /** Trampa: acostado, brazos al frente y arriba al ritmo de una sentadilla. */
    fun lying(extraBed: Double = 0.0, wob: Double = 1.0) =
        motion(reps20(period, pause), period, amp * q.u(0.8, 1.3), 90.0 - bed - extraBed, wobble * wob, pause)
    fun falsifications(): List<Pair<String, List<Smp>>> = listOf(
        "quieto" to periodic(20, 0.3, 0.0, hold, 1.0),
        "caminar" to periodic(20, q.u(1.5, 2.1), q.u(1.0, 2.0), hold, 4.0),
        "sacudir" to periodic(20, q.u(2.5, 4.0), q.u(3.0, 6.0), hold, 5.0),
        "inclinarse" to motion(5, 4000.0, q.u(0.6, 1.2), hold, q.u(60.0, 80.0), 0.0),
        "brazos lento" to motion(reps20(period, pause), period, amp, hold, q.u(0.0, 6.0), pause),
        "brazos rápido" to motion(20, 800.0, q.u(6.0, 11.0), hold, q.u(25.0, 55.0), 200.0),
    )
}

fun seg(name: String, truth: Int, sig: List<Smp>): Segment {
    val s = Segment(name, truth > 0)
    for (x in sig) s.add(x.a, x.t, 0f, cos(Math.toRadians(x.phi)).toFloat(), sin(Math.toRadians(x.phi)).toFloat())
    s.truth = truth
    return s
}

/** Pasos de la preconfiguración en el orden de la app (3 series; la falsación acostado va al final). */
fun preconfig(u: User, withLying: Boolean): List<Segment> {
    val f = u.falsifications().map { seg(it.first, 0, it.second) }
    val s = (1..3).map { seg("Serie $it", 10, u.squats(10)) }
    return listOf(s[0], f[0], f[1], f[2], s[1], f[3], f[4], f[5], s[2]) + (if (withLying) listOf(seg("acostado", 0, u.lying())) else emptyList())
}

/** Datos nuevos: sentadillas normales y «de mañana» (más suaves), falsaciones y variantes de la trampa acostado. */
class Held(u: User) {
    val squats = seg("sentadillas", 20, u.squats(20))
    val morning = seg("sentadillas suaves", 20, u.squats(20, 0.8))
    val standing = u.falsifications().map { seg(it.first, 0, it.second) }
    val lying = seg("acostado", 0, u.lying())
    val lyingPillow = seg("acostado +10° de almohada", 0, u.lying(extraBed = 10.0))
    val lyingWobble = seg("acostado, gira más el teléfono", 0, u.lying(wob = 1.4))
}

class Score(val name: String) {
    var n = 0; var sens = 0.0; var sensM = 0.0; var lowSens = 0
    var fpStand = 0.0; var anyStand = 0
    val lyFp = DoubleArray(3); val lyAny = IntArray(3)
    val flat = ArrayList<Float>()
    val byStep = LinkedHashMap<String, Int>()
    fun add(c: Calibration, h: Held) {
        n++
        val s = Tuner.replay(h.squats, c) / 20.0
        sens += s.coerceAtMost(1.0); if (s < 0.8) lowSens++
        sensM += (Tuner.replay(h.morning, c) / 20.0).coerceAtMost(1.0)
        val fs = h.standing.sumOf { st -> Tuner.replay(st, c).also { if (it > 0) byStep[st.name] = (byStep[st.name] ?: 0) + 1 } }
        fpStand += fs; if (fs > 0) anyStand++
        for ((i, l) in listOf(h.lying, h.lyingPillow, h.lyingWobble).withIndex()) {
            val d = Tuner.replay(l, c); lyFp[i] += d.toDouble(); if (d > 0) lyAny[i]++
        }
        flat.add(c.flatMaxDeg)
    }
    fun p(x: Double) = "%5.1f %%".format(100 * x)
    fun print() {
        val veto = flat.count { it < SquatCounter.NO_FLAT_LIMIT }
        println("%-34s sens %s (suaves %s; <80 %%: %s) | de pie: FP/usuario %.2f, con alguno %s | acostado con alguna contada: igual %s · +almohada %s · +giro %s | veto acostado en %s (mediana %.0f°)".format(
            name, p(sens / n), p(sensM / n), p(lowSens.toDouble() / n), fpStand / n, p(anyStand.toDouble() / n),
            p(lyAny[0].toDouble() / n), p(lyAny[1].toDouble() / n), p(lyAny[2].toDouble() / n), p(veto.toDouble() / n),
            flat.sorted()[flat.size / 2]))
        println("    usuarios con falsos positivos de pie, por prueba: $byStep")
    }
}

/** Una mañana honesta: un rato acostado, levantarse y 10 sentadillas «de mañana» (más suaves que en la preconfiguración). */
fun morning(u: User): Segment {
    val q = u.q
    val lie = u.periodic(q.u(40.0, 120.0).toInt(), 0.2, 0.05, 90.0 - u.bed, 3.0)
    var t = lie.last().t
    val up = (0 until 3000 step 20).map { Smp((1.5 * sin(2 * PI * it / 1500.0) + q.g(u.noise)).toFloat(), t + 20 + it, (90.0 - u.bed) + (u.hold - 90.0 + u.bed) * it / 3000.0) }
    t = up.last().t + 20
    val sq = u.squats(10, 0.8).map { Smp(it.a, it.t + t, it.phi) }
    return seg("mañana", 10, lie + up + sq)
}

val USERS = (System.getenv("SIM_USERS") ?: "60").toInt()
val MORNINGS = 30
val before = Score("Antes (sin falsación acostado)")
val grid = Score("Grilla bayesiana (90/60/40°)")
val center = Score("Grilla + centrado (0 generaciones)")
val ga = Score("Grilla + genético")
val gaM = Score("… tras $MORNINGS mañanas")
val gridM = Score("Grilla tras $MORNINGS mañanas (método anterior)")
val GRID_ALARMS = System.getenv("SIM_GRID_ALARMS") != null // lento: re-analiza cada mañana con toda la grilla
val t0 = System.currentTimeMillis()
var gaMs = 0L; var learnMs = 0L; var switches = 0
for (id in 1..USERS) {
    val u = User(Gen(1000L + id))
    val h = Held(u)
    val steps = preconfig(u, true)
    val a = BayesTuner(); for (s in steps.dropLast(1)) a.add(s, 0.5)
    before.add(a.best(0.5).first, h)
    val b = BayesTuner(); for (s in steps) b.add(s, 0.5)
    grid.add(b.best(0.5).first, h)
    center.add(Evolver.fromGrid(b, 0.5, id.toLong(), 0).pop[0].cal, h)
    val t1 = System.currentTimeMillis()
    val e = Evolver.fromGrid(b, 0.5, id.toLong())
    gaMs += System.currentTimeMillis() - t1
    require(e.utility(e.pop[0], 0.5) >= b.best(0.5).second.let { 0.5 * it.sens + 0.5 * it.spec } - 1e-6) { "el genético no puede empeorar la grilla" }
    ga.add(e.pop[0].cal, h)
    val mornings = List(MORNINGS) { morning(u) }
    val t2 = System.currentTimeMillis()
    for (m in mornings) if (e.learnFromAlarm(m, 0.5) != null) switches++
    learnMs += System.currentTimeMillis() - t2
    gaM.add(e.pop[0].cal, h)
    if (GRID_ALARMS) {
        var cur = b.bestIndex(0.5)
        for (m in mornings) {
            b.add(m, 0.5, Tuner.ALARM_EVIDENCE, maxOf(1, (Tuner.FALSIFICATION_MS / b.windowMs).toInt()))
            val best = b.bestIndex(0.5)
            if (best != cur && Tuner.clearlyBetter(b.posterior(best), b.posterior(cur), 0.5)) cur = best
        }
        gridM.add(BayesTuner.calibration(cur), h)
    }
}
println("$USERS usuarios simulados, ${(System.currentTimeMillis() - t0) / 1000} s (genético: ${gaMs / USERS} ms por usuario; cada mañana ${learnMs / USERS / MORNINGS} ms; cambios de detección por usuario en $MORNINGS mañanas: %.1f)".format(switches.toDouble() / USERS))
for (s in listOf(before, grid, center, ga, gaM) + (if (GRID_ALARMS) listOf(gridM) else emptyList())) s.print()
tasks.register("coreTest") { doLast { println("simulación terminada") } }
