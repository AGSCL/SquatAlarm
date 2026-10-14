
// ---------------------------------------------------------------------------------------------
// Señales sintéticas (aceleración vertical m/s², + = arriba; inclinación del teléfono en grados)
// y asserts sobre el contador, el motor bayesiano y la distribución Beta.
// ---------------------------------------------------------------------------------------------
class Smp(val a: Float, val t: Long, val tiltDeg: Double)

fun Smp.gx() = 0f
fun Smp.gy() = kotlin.math.cos(Math.toRadians(tiltDeg)).toFloat()
fun Smp.gz() = kotlin.math.sin(Math.toRadians(tiltDeg)).toFloat()

/** n repeticiones de un ciclo seno (negativo → positivo) con inclinación máxima en el fondo y pausa. */
fun reps(n: Int, periodMs: Int, amp: Float, tiltMax: Double, pauseMs: Int, dt: Int = 20): List<Smp> {
    var t = 0L
    val out = ArrayList<Smp>()
    repeat(n) {
        for (k in 0 until periodMs / dt) {
            val phase = 2 * PI * k * dt / periodMs
            val s = sin(phase / 2)
            out.add(Smp((-amp * sin(phase)).toFloat(), t, tiltMax * s * s)); t += dt
        }
        repeat(pauseMs / dt) { out.add(Smp(0f, t, 0.0)); t += dt }
    }
    return out
}

fun squats(n: Int, amp: Float = 2.5f, periodMs: Int = 2000, pauseMs: Int = 800) = reps(n, periodMs, amp, 25.0, pauseMs)

fun periodic(seconds: Int, freqHz: Double, amp: Float, tiltAmp: Double, dt: Int = 20): List<Smp> =
    (0 until seconds * 1000 step dt).map { t ->
        val ph = 2 * PI * freqHz * t / 1000.0
        Smp((amp * sin(ph)).toFloat(), t.toLong(), tiltAmp * kotlin.math.abs(sin(ph)))
    }

fun noise(seconds: Int, amp: Float, dt: Int = 20): List<Smp> {
    var seed = 12345L
    return (0 until seconds * 1000 step dt).map { t ->
        seed = seed * 6364136223846793005L + 1442695040888963407L
        val u = ((seed ushr 11).toDouble() / (1L shl 53)) * 2 - 1
        Smp((amp * u).toFloat(), t.toLong(), 1.0 * u)
    }
}

fun count(sig: List<Smp>, c: Calibration): Int {
    val k = c.counter()
    for (s in sig) k.process(s.a, s.t, s.gx(), s.gy(), s.gz())
    return k.count
}

fun segment(name: String, positive: Boolean, truth: Int, sig: List<Smp>): Segment {
    val s = Segment(name, positive)
    for (x in sig) s.add(x.a, x.t, x.gx(), x.gy(), x.gz())
    s.truth = truth
    s.liveCount = count(sig, Tuner.PERMISSIVE)
    return s
}

fun near(x: Double, ref: Double, tol: Double = 1e-6) = kotlin.math.abs(x - ref) <= tol

// 1) Contador con valores por defecto (regresión)
require(count(squats(10), Calibration.DEFAULT) == 10) { "10 sentadillas deben contar 10" }
require(count(periodic(5, 3.0, 4f, 5.0), Calibration.DEFAULT) == 0) { "sacudir 5 s a 3 Hz debe contar 0" }
require(count(squats(8, amp = 1.8f, periodMs = 3000, pauseMs = 2500), Calibration(0.8f, 0.8f, 350L, 4000L)) == 8) { "8 lentas" }

// 2) Vetos: inclinación mínima rechaza brazos con el teléfono vertical; pico máximo rechaza brazos rápidos
val armsSlow = reps(6, 2000, 2.5f, 2.0, 800)
val armsFast = reps(20, 800, 9f, 40.0, 200)
require(count(armsSlow, Calibration.DEFAULT) == 6) { "sin vetos, los brazos lentos engañan al contador" }
require(count(armsSlow, Calibration.DEFAULT.copy(tiltMinDeg = 10f)) == 0) { "inclinación mínima 10° debe vetar brazos verticales" }
require(count(armsFast, Calibration.DEFAULT.copy(maxPeak = 7f)) == 0) { "pico máximo 7 debe vetar brazos rápidos" }
require(count(squats(10), Calibration.DEFAULT.copy(tiltMinDeg = 10f, maxPeak = 7f)) == 10) { "las sentadillas pasan los vetos" }
run {
    val k = Calibration.DEFAULT.counter()
    var last: SquatCounter.Squat? = null
    for (s in armsSlow) k.process(s.a, s.t, s.gx(), s.gy(), s.gz())?.let { last = it }
    require(last!!.tiltDeg in 1.5f..2.5f) { "inclinación medida ${last!!.tiltDeg}" }
}

// 3) Distribución Beta contra SciPy
require(near(BetaDist.lnGamma(10.0), 12.801827480081469, 1e-9) && near(BetaDist.lnGamma(0.3), 1.0957979948180756, 1e-9) &&
    near(BetaDist.lnGamma(171.5), 709.1431630309283, 1e-7)) { "lnGamma" }
for ((ab, ref) in listOf(
    (31.0 to 1.0) to (0.88781125307763 to 0.9991836299281533),
    (2.5 to 7.3) to (0.05050434517636552 to 0.5528002669507333),
    (49.0 to 3.0) to (0.8654135088703359 to 0.9877009117447599),
    (1.0 to 1.0) to (0.025 to 0.975),
    (33.0 to 12.0) to (0.5966195922641409 to 0.8504231562346919),
    (0.7 to 0.4) to (0.014955932259258585 to 0.9998406513277163),
)) {
    val lo = BetaDist.quantile(0.025, ab.first, ab.second)
    val hi = BetaDist.quantile(0.975, ab.first, ab.second)
    require(near(lo, ref.first, 1e-8) && near(hi, ref.second, 1e-8)) { "cuantiles Beta$ab: $lo $hi vs $ref" }
}
require(near(BetaDist.cdf(0.8, 11.0, 1.0), 0.08589934592000005, 1e-10)) { "cdf 1" }
require(near(BetaDist.cdf(0.3, 2.5, 7.3), 0.6642381992386518, 1e-10)) { "cdf 2" }
require(near(BetaDist.cdf(0.9, 33.0, 12.0), 0.9990155258558736, 1e-10)) { "cdf 3" }
require(near(BetaDist.cdf(0.5, 0.7, 0.4), 0.3400088433511981, 1e-10)) { "cdf 4" }

// 4) Preconfiguración intercalada (3 series de 10 + 6 falsaciones) con actualización bayesiana por paso
val steps = listOf(
    segment("Serie 1", true, 10, squats(10, amp = 2.5f)),
    segment("Quieto", false, 0, noise(20, 0.15f)),
    segment("Caminar", false, 0, periodic(20, 1.8, 1.5f, 4.0)),
    segment("Serie 2", true, 10, squats(10, amp = 2.2f, periodMs = 1800)),
    segment("Sacudir", false, 0, periodic(20, 3.0, 4f, 5.0)),
    segment("Inclinarse", false, 0, reps(5, 4000, 0.9f, 70.0, 0)),
    segment("Serie 3", true, 10, squats(10, amp = 2.8f, periodMs = 2200, pauseMs = 1200)),
    segment("Brazos lento", false, 0, armsSlow),
    segment("Brazos rápido", false, 0, armsFast),
)
require(steps[7].liveCount > 0 && steps[8].liveCount > 0) { "los brazos deben engañar a los umbrales permisivos (si no, la falsación no prueba nada)" }

val tuner = BayesTuner()
val t0 = System.currentTimeMillis()
for (s in steps) tuner.add(s, 0.5)
val ms = System.currentTimeMillis() - t0
require(tuner.history.size == steps.size) { "un snapshot por paso" }
require(tuner.windowMs in 2500L..3000L) { "ventana = duración serie 1 / 10 = ${tuner.windowMs}" }
require(!tuner.history[0].posterior.hasNeg || tuner.history[0].posterior.spec < 0.6) { "tras la serie 1 casi no hay datos negativos" }

val (cal, post) = tuner.best(0.5)
val c = tuner.counts(tuner.bestIndex(0.5))
println("Elegido: bajada ${cal.thresholdDown} subida ${cal.thresholdUp} minCiclo ${cal.minCycleMs} pico≤${cal.maxPeak} " +
    "inclinación ${cal.tiltMinDeg}–${cal.tiltMaxDeg}° | tp=${c.tp} fn=${c.fn} fp=${c.fp} tn=${c.tn} | " +
    "sens ${post.sens} ICr ${post.sensCrI()} esp ${post.spec} ICr ${post.specCrI()} | $ms ms para ${steps.size} pasos " +
    "(${BayesTuner.N} combinaciones)")
require(c.tp == 30 && c.fn == 0 && c.fp == 0) { "debe separar perfectamente los datos sintéticos" }
require(Tuner.replay(steps[7], cal) == 0 && Tuner.replay(steps[8], cal) == 0) { "la θ elegida veta los brazos" }
require(steps.filter { it.positive }.all { Tuner.replay(it, cal) == 10 }) { "y cuenta todas las sentadillas" }
require(near(post.sens, 31.0 / 32.0, 1e-12)) { "posterior Beta(1+30, 1+0)" }
require(cal.calibrated && cal.nSquats == 30) { "metadatos" }

// 5) Equivalencia: los conteos acumulados del tuner = evaluar esa θ desde cero (vetos post hoc = vetos en vivo)
var rng = 7L
val probe = listOf(tuner.bestIndex(0.5), 0, BayesTuner.N - 1) + (1..40).map {
    rng = rng * 6364136223846793005L + 1442695040888963407L
    ((rng ushr 33) % BayesTuner.N).toInt()
}
for (i in probe) {
    val a = tuner.counts(i)
    val b = Tuner.evaluate(steps, BayesTuner.calibration(i), tuner.windowMs)
    require(a.tp == b.tp && a.fn == b.fn && a.fp == b.fp && a.tn == b.tn) {
        "θ#$i: tuner (${a.tp},${a.fn},${a.fp},${a.tn}) ≠ evaluate (${b.tp},${b.fn},${b.fp},${b.tn})"
    }
}

// 6) El posterior aprende: sin brazos en los datos, la θ elegida no veta brazos; con brazos, sí
val noArms = BayesTuner()
for (s in steps.take(7)) noArms.add(s, 0.5)
val calNoArms = noArms.best(0.5).first
require(Tuner.replay(steps[7], calNoArms) > 0) { "sin evidencia de brazos no se impone el veto (empate → menos restrictivo)" }

// 7) Peso: más peso a la sensibilidad nunca baja la sensibilidad posterior (estática comparativa del argmax)
val hiS = tuner.best(0.95).second
val loS = tuner.best(0.05).second
require(hiS.sens >= loS.sens - 1e-12 && loS.spec >= hiS.spec - 1e-12) { "monotonía del peso" }

println("CORE OK")
tasks.register("coreTest") { doLast { println("coreTest: todos los asserts pasaron") } }
