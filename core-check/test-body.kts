
// ---------------------------------------------------------------------------------------------
// Señales sintéticas (aceleración vertical, m/s²; + = arriba) y asserts sobre el núcleo.
// ---------------------------------------------------------------------------------------------
fun squats(n: Int, periodMs: Int = 2000, amp: Float = 2.5f, pauseMs: Int = 800, dt: Int = 20): List<Pair<Float, Long>> {
    var t = 0L
    val out = ArrayList<Pair<Float, Long>>()
    repeat(n) {
        for (k in 0 until periodMs / dt) {                     // una sentadilla = un ciclo seno completo
            val phase = 2 * PI * k * dt / periodMs
            out.add((-amp * sin(phase)).toFloat() to t); t += dt // negativo (bajar) → positivo (frenar/subir)
        }
        repeat(pauseMs / dt) { out.add(0f to t); t += dt }
    }
    return out
}

fun periodic(seconds: Int, freqHz: Float, amp: Float, dt: Int = 20): List<Pair<Float, Long>> =
    (0 until seconds * 1000 step dt).map { t -> (amp * sin(2 * PI * freqHz * t / 1000.0)).toFloat() to t.toLong() }

fun noise(seconds: Int, amp: Float, dt: Int = 20): List<Pair<Float, Long>> {
    var seed = 12345L
    return (0 until seconds * 1000 step dt).map { t ->
        seed = (seed * 6364136223846793005L + 1442695040888963407L)
        val u = ((seed ushr 11).toDouble() / (1L shl 53)) * 2 - 1
        (amp * u).toFloat() to t.toLong()
    }
}

fun runCounter(sig: List<Pair<Float, Long>>, c: Calibration): Int {
    val k = SquatCounter(c.thresholdDown, c.thresholdUp, c.minCycleMs, c.maxCycleMs)
    for ((a, t) in sig) k.process(a, t)
    return k.count
}

fun segment(name: String, positive: Boolean, truth: Int, sig: List<Pair<Float, Long>>): Segment {
    val s = Segment(name, positive)
    for ((a, t) in sig) s.add(a, t)
    s.truth = truth
    s.liveCount = runCounter(sig, Tuner.PERMISSIVE)
    return s
}

// 1) Contador con los valores por defecto
require(runCounter(squats(10), Calibration.DEFAULT) == 10) { "10 sentadillas normales deben contar 10" }
require(runCounter(periodic(5, 3f, 4f), Calibration.DEFAULT) == 0) { "sacudir 5 s a 3 Hz debe contar 0" }
require(runCounter(squats(8, periodMs = 3000, amp = 1.8f, pauseMs = 2500), Calibration(0.8f, 0.8f, 350L, 4000L)) == 8) { "8 sentadillas lentas deben contar 8" }

// 2) Preconfiguración sintética: 3 series de 10 + 4 pruebas de falsación
val segs = listOf(
    segment("Serie 1", true, 10, squats(10, amp = 2.5f)),
    segment("Serie 2", true, 10, squats(10, amp = 2.2f, periodMs = 1800)),
    segment("Serie 3", true, 10, squats(10, amp = 2.8f, periodMs = 2200, pauseMs = 1200)),
    segment("Quieto", false, 0, noise(20, 0.15f)),
    segment("Caminar", false, 0, periodic(20, 1.8f, 1.5f)),
    segment("Sacudir", false, 0, periodic(20, 3f, 4f)),
    segment("Inclinarse", false, 0, periodic(20, 0.25f, 0.9f)),
)
require(segs.take(3).all { it.liveCount == 10 }) { "con umbrales permisivos las series deben contar 10: ${segs.map { it.liveCount }}" }
require(segs[6].liveCount > 0) { "inclinarse lento debe engañar a los umbrales permisivos (si no, la prueba no falsa nada)" }

val window = Tuner.windowMs(segs)
require(window in 2500L..3500L) { "ventana ≈ (duración series / 30) ≈ 3000 ms, fue $window" }

val permissive = Tuner.evaluate(segs, Tuner.PERMISSIVE)
val t0 = System.currentTimeMillis()
val (tuned, m) = Tuner.tune(segs, 0.5f)
println("permisivo: sens=${permissive.sens} esp=${permissive.spec} | ajustado: sens=${m.sens} esp=${m.spec} " +
    "umbrales=${tuned.thresholdDown}/${tuned.thresholdUp} minCycle=${tuned.minCycleMs} maxCycle=${tuned.maxCycleMs} " +
    "ventana=$window ms | tune: ${System.currentTimeMillis() - t0} ms sobre ${segs.sumOf { it.a.size }} muestras")
require(permissive.spec < 1f) { "los umbrales permisivos deben tener falsos positivos en la falsación" }
require(m.sens == 1f && m.spec == 1f) { "el ajuste debe separar perfectamente los datos sintéticos" }
require(tuned.thresholdDown > 0.9f && tuned.thresholdDown < 2.2f) { "umbral bajada entre inclinarse (0.9) y la sentadilla más débil (2.2): ${tuned.thresholdDown}" }
require(tuned.calibrated && tuned.nSquats == 30 && tuned.sens == 1f) { "metadatos de la calibración" }
require(segs.take(3).all { Tuner.replay(it, tuned) == 10 } && segs.drop(3).all { Tuner.replay(it, tuned) == 0 }) { "replay con los umbrales ajustados" }

// 3) El usuario corrige el conteo: dijo 8 pero la app contó 10 → 8 aciertos y 2 falsos positivos
val over = segment("Serie X", true, 8, squats(10))
val mo = Tuner.evaluate(listOf(over), Calibration.DEFAULT)
require(mo.tp == 8 && mo.fp == 2 && mo.fn == 0) { "tp=${mo.tp} fp=${mo.fp} fn=${mo.fn}" }
val under = segment("Serie Y", true, 10, squats(7))
val mu = Tuner.evaluate(listOf(under), Calibration.DEFAULT)
require(mu.tp == 7 && mu.fn == 3 && mu.sens < 0.71f && mu.sens > 0.69f) { "tp=${mu.tp} fn=${mu.fn} sens=${mu.sens}" }

// 4) Priorizar sensibilidad baja los umbrales, priorizar especificidad los sube (o igual)
val (lowT, _) = Tuner.tune(segs, 0.95f)
val (highT, _) = Tuner.tune(segs, 0.05f)
require(lowT.thresholdDown <= highT.thresholdDown) { "peso→sens ${lowT.thresholdDown} debe ser ≤ peso→esp ${highT.thresholdDown}" }

println("CORE OK")
tasks.register("coreTest") { doLast { println("coreTest: todos los asserts pasaron") } }
