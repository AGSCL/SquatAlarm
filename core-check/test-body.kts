
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
    // Tomar el teléfono (frenazo con 70° de inclinación, sin lóbulo positivo) y a 1,5 s hacer una sentadilla:
    // el ciclo nuevo debe medirse desde su propio inicio, no desde la orientación del gesto anterior.
    val strict = Calibration(1.3f, 1.3f, 500L, 5000L, maxPeak = 5.5f, tiltMinDeg = 10f, tiltMaxDeg = 60f)
    val pickup = (0 until 300 step 20).map { Smp(-2.5f * sin(PI * it / 300).toFloat(), it.toLong(), 70.0) } +
        (300 until 1800 step 20).map { Smp(0f, it.toLong(), 0.0) }
    val squat = squats(1).map { Smp(it.a, it.t + 1800, it.tiltDeg) }
    require(count(squats(1), strict) == 1) { "la sentadilla sola cuenta" }
    require(count(pickup + squat, strict) == 1) { "tras tomar el teléfono, la sentadilla siguiente también cuenta" }
}
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

// 8) Respaldo de parámetros: ida y vuelta con tipos exactos; archivos ajenos o mal editados se rechazan
val prefsIn = mapOf<String, Any>(
    "hour" to 7, "enabled" to true, "strictness" to 1.0f, "cal_thr_down" to 2.7f, "cal_min_cycle" to 300L,
    "cal_max_peak" to SquatCounter.NO_LIMIT, "sensor_package" to "com.theswitchbot.switchbot",
    "sensor_text" to "sensor espacios: Movimiento detectado", "mission" to 1,
    "is_ringing" to true, "ringing_since" to 123L, // estado de una alarma sonando: no se exporta
)
val file = PrefsCodec.encode(prefsIn)
val back = PrefsCodec.decode(file)
require(back == prefsIn - "is_ringing" - "ringing_since") { "ida y vuelta: $back" }
require(back["cal_min_cycle"] is Long && back["cal_thr_down"] is Float && back["hour"] is Int) { "tipos exactos" }
fun rejects(text: String) = try { PrefsCodec.decode(text); false } catch (e: IllegalArgumentException) { true }
require(rejects("hola\nhour=7")) { "sin encabezado → rechazar" }
require(rejects(PrefsCodec.HEADER + "\nhour=siete")) { "tipo inválido → rechazar (evita que la app se caiga al leerlo)" }
require(rejects(PrefsCodec.HEADER + "\ncal_thr_down=NaN")) { "NaN → rechazar" }
require(rejects(PrefsCodec.HEADER + "\nsin_signo_igual")) { "línea sin = → rechazar" }
require(PrefsCodec.decode(PrefsCodec.HEADER + "\nclave_de_otra_version=1\n\n# comentario\nhour=8") == mapOf("hour" to 8)) { "ignora claves desconocidas" }
require(PrefsCodec.decode(PrefsCodec.HEADER + "\nsensor_text=a=b").getValue("sensor_text") == "a=b") { "texto con =" }
require(rejects(PrefsCodec.HEADER + "\nstrictness=0")) { "exigencia 0 contaría cualquier movimiento → rechazar" }
require(rejects(PrefsCodec.HEADER + "\nsquats_required=100000")) { "sentadillas fuera de rango → rechazar" }
require(rejects(PrefsCodec.HEADER + "\nsafety_timeout_min=0") && rejects(PrefsCodec.HEADER + "\nhour=24")) { "rangos de la interfaz" }
require(rejects(PrefsCodec.HEADER + "\nvolume_loud=0") && rejects(PrefsCodec.HEADER + "\nvolume_soft=0.61")) { "la alarma fuerte no puede quedar muda" }
require(PrefsCodec.decode("\uFEFF" + PrefsCodec.HEADER + "\r\nhour=6\r\n") == mapOf("hour" to 6)) { "BOM y fin de línea de Windows" }
run {
    // Todo lo que la app puede guardar (extremos de la interfaz y del tuner) se puede volver a importar.
    val extremes = listOf(
        mapOf("hour" to 0, "minute" to 59, "repeat_days" to 127, "squats_required" to 3, "strictness" to 0.5f,
            "safety_timeout_min" to 180, "mission" to 0, "cal_sens" to -1f, "cal_tilt_max" to 180f,
            "volume_soft" to 0.6f, "volume_loud" to 0.3f, "soft_start" to true, "sound_soft" to "content://media/internal/audio/media/7"),
        mapOf("hour" to 23, "minute" to 0, "repeat_days" to 0, "squats_required" to 40, "strictness" to 1.8f,
            "safety_timeout_min" to 5, "mission" to 1, "cal_sens" to 1f, "cal_tilt_min" to 0f,
            "volume_soft" to 0.05f, "volume_loud" to 1f, "soft_start" to false, "sound_loud" to ""),
    )
    for (m in extremes) require(PrefsCodec.decode(PrefsCodec.encode(m)) == m) { "extremos válidos: $m" }
    for (i in listOf(0, BayesTuner.N - 1, tuner.bestIndex(0.5))) {
        val cal = BayesTuner.calibration(i)
        val m = mapOf<String, Any>(
            "cal_thr_down" to cal.thresholdDown, "cal_thr_up" to cal.thresholdUp, "cal_min_cycle" to cal.minCycleMs,
            "cal_max_cycle" to cal.maxCycleMs, "cal_max_peak" to cal.maxPeak, "cal_tilt_min" to cal.tiltMinDeg, "cal_tilt_max" to cal.tiltMaxDeg,
        )
        require(PrefsCodec.decode(PrefsCodec.encode(m)) == m) { "θ#$i exportada debe poder importarse" }
    }
    val d = Calibration.DEFAULT
    require(PrefsCodec.decode(PrefsCodec.encode(mapOf("cal_thr_down" to d.thresholdDown, "cal_min_cycle" to d.minCycleMs, "cal_max_cycle" to d.maxCycleMs))).size == 3) { "valores por defecto" }
}

// 9) Regla del sensor: solo una notificación NUEVA, de esa app y con ese texto
val pkg = "com.theswitchbot.switchbot"
val want = "sensor espacios: Movimiento detectado"
require(SensorMatch.matches(pkg, "sensor espacios: Movimiento detectado", "Pulse para ver los detalles.", 2000L, pkg, want, 1000L))
require(SensorMatch.matches(pkg, "SENSOR ESPACIOS: movimiento detectado", "", 2000L, pkg, " $want ", 1000L)) { "sin distinguir mayúsculas ni espacios" }
require(!SensorMatch.matches(pkg, want, "", 999L, pkg, want, 1000L)) { "anterior al inicio de la alarma → no" }
require(!SensorMatch.matches("com.whatsapp", want, "", 2000L, pkg, want, 1000L)) { "otra app con el mismo texto → no" }
require(!SensorMatch.matches(pkg, "sensor pieza: Movimiento detectado", "", 2000L, pkg, want, 1000L)) { "otro sensor → no" }
require(!SensorMatch.matches(pkg, want, "", 2000L, pkg, "  ", 1000L) && !SensorMatch.matches(pkg, want, "", 2000L, "", "", 1000L)) { "sin texto → nunca" }
require(!SensorMatch.matches("com.whatsapp", "Hola", "", 2000L, "", "Hola", 1000L) && !SensorMatch.configured("", "Hola")) { "cualquier app con texto corto → no (se usan sentadillas)" }
require(SensorMatch.configured(pkg, "Hola") && SensorMatch.matches(pkg, "Hola", "", 2000L, pkg, "Hola", 1000L)) { "con app elegida, el texto corto sirve" }
require(SensorMatch.matches("com.otra.app", want, "", 2000L, "", "movimiento detectado", 1000L)) { "texto escrito a mano sin app → cualquier app" }
require(!SensorMatch.matches("com.otra.app", "Hola", "", 2000L, "", "movimiento detectado", 1000L)) { "…pero con ese texto" }
require(!SensorMatch.matches(pkg, want, "", 999L, "", want, 1000L)) { "…y nueva" }

// 10) Inicio suave: 90 s suave, 120 s de silencio, después fuerte; sin inicio suave, fuerte desde el principio
require(RingPlan.phaseAt(0L, true) == RingPlan.Phase.SOFT && RingPlan.phaseAt(89_999L, true) == RingPlan.Phase.SOFT)
require(RingPlan.phaseAt(90_000L, true) == RingPlan.Phase.SILENT && RingPlan.phaseAt(209_999L, true) == RingPlan.Phase.SILENT)
require(RingPlan.phaseAt(210_000L, true) == RingPlan.Phase.LOUD && RingPlan.phaseAt(3_600_000L, true) == RingPlan.Phase.LOUD)
require(RingPlan.phaseAt(0L, false) == RingPlan.Phase.LOUD) { "sin inicio suave" }
require(RingPlan.phaseAt(-5_000L, true) == RingPlan.Phase.SOFT) { "reloj atrasado: no salta a fuerte" }
require(RingPlan.volumeIndex(1f, 7) == 7 && RingPlan.volumeIndex(0.2f, 7) == 1 && RingPlan.volumeIndex(0.5f, 15) == 8)
require(RingPlan.untilText(7 * 3_600_000L + 23 * 60_000L) == "7 h 23 min" && RingPlan.untilText(3_600_000L) == "1 h")
require(RingPlan.untilText(30_000L) == "1 min" && RingPlan.untilText(45 * 60_000L) == "45 min") { "minutos hacia arriba" }
require(RingPlan.untilText(7 * 3_600_000L + 22 * 60_000L + 1) == "7 h 23 min" && RingPlan.untilText(-5L) == "0 min")
require(RingPlan.volumeIndex(0.05f, 7) == 1 && RingPlan.volumeIndex(0f, 15) == 1) { "nunca muda" }

// 11) Aprendizaje desde las alarmas: índice de θ, persistencia, peso 0,2 y regla «claramente mejor»
for (i in listOf(0, BayesTuner.N - 1, tuner.bestIndex(0.5), 12345, 200_001)) {
    require(BayesTuner.indexOf(BayesTuner.calibration(i)) == i) { "indexOf(calibration($i))" }
}
require(BayesTuner.indexOf(Calibration.DEFAULT) == -1) { "los valores por defecto no están en la grilla" }
val saved = java.io.ByteArrayOutputStream().also { tuner.write(it) }.toByteArray()
fun loadTuner() = BayesTuner.read(java.io.ByteArrayInputStream(saved))!!
run {
    val back = loadTuner()
    require(back.windowMs == tuner.windowMs) { "ventana guardada" }
    for (i in probe) {
        val a = tuner.posterior(i)
        val b = back.posterior(i)
        require(a.aS == b.aS && a.bS == b.bS && a.aE == b.aE && a.bE == b.bE) { "posterior θ#$i ida y vuelta" }
    }
    require(BayesTuner.read(java.io.ByteArrayInputStream(ByteArray(16))) == null) { "archivo ajeno → null" }
}
val i0 = tuner.bestIndex(0.5)
val softSquats = segment("Alarma", true, 10, squats(10, amp = 1.0f))
require(Tuner.replay(softSquats, BayesTuner.calibration(i0)) < 10) { "la θ de la preconfiguración no ve estas sentadillas suaves" }
run {
    val full = loadTuner().also { it.add(softSquats, 0.5, 1f) }
    val fifth = loadTuner().also { it.add(softSquats, 0.5, Tuner.ALARM_EVIDENCE) }
    val dFull = full.posterior(i0).bS - tuner.posterior(i0).bS
    val dFifth = fifth.posterior(i0).bS - tuner.posterior(i0).bS
    require(dFull > 0 && near(dFifth, 0.2 * dFull, 1e-3)) { "una alarma suma 0,2 de un tramo: $dFifth vs $dFull" }
}
require(!Tuner.clearlyBetter(tuner.posterior(i0), tuner.posterior(i0), 0.5)) { "igual → no" }
require(Tuner.clearlyBetter(Posterior(101.0, 1.0, 101.0, 1.0), Posterior(51.0, 51.0, 51.0, 51.0), 0.5)) { "mucho mejor → sí" }
require(!Tuner.clearlyBetter(Posterior(4.0, 1.0, 4.0, 1.0), Posterior(3.0, 2.0, 3.0, 2.0), 0.5)) { "con poca evidencia → no" }
run {
    // Una alarma con mucho rato quieto aporta como mucho las ventanas negativas de una falsación (× 0,2).
    val lazy = segment("Alarma lenta", true, 10, noise(150, 0.1f) + squats(10).map { Smp(it.a, it.t + 150_000L, it.tiltDeg) })
    val t2 = loadTuner()
    Tuner.learnFromAlarm(t2, lazy, 0.5, i0)
    val cap = Tuner.FALSIFICATION_MS / tuner.windowMs
    val dNeg = (t2.posterior(i0).aE + t2.posterior(i0).bE) - (tuner.posterior(i0).aE + tuner.posterior(i0).bE)
    require(lazy.negWindows > cap && dNeg <= Tuner.ALARM_EVIDENCE * cap + 1e-4) { "tope negativo: $dNeg con ${lazy.negWindows} ventanas" }
}
// Qué tramo de la alarma se graba: los últimos 3 min, o desde 30 s antes de la primera sentadilla (máx. 10 min)
require(Tuner.alarmKeepFrom(1_000_000L, 0L) == 820_000L) { "sin sentadillas: últimos 3 min" }
require(Tuner.alarmKeepFrom(1_000_000L, 700_000L) == 670_000L) { "desde la primera sentadilla" }
require(Tuner.alarmKeepFrom(1_000_000L, 950_000L) == 820_000L) { "sentadillas recientes: últimos 3 min" }
require(Tuner.alarmKeepFrom(1_000_000L, 100_000L) == 400_000L) { "tope de 10 min" }
run {
    // Enfriamiento: la preconfiguración pesa más; una alarma distinta no mueve θ, varias consistentes sí.
    val learner = loadTuner()
    var switchedAt = -1
    for (k in 1..60) {
        val seg = segment("Alarma $k", true, 10, squats(10, amp = 1.0f))
        val new = Tuner.learnFromAlarm(learner, seg, 0.5, i0)
        if (new != null) {
            switchedAt = k
            require(Tuner.replay(seg, BayesTuner.calibration(new)) == 10) { "la θ nueva cuenta las sentadillas suaves" }
            require(steps.filter { it.positive }.all { Tuner.replay(it, BayesTuner.calibration(new)) == 10 }) { "y sigue contando las de la preconfiguración" }
            break
        }
    }
    println("Aprendizaje: la θ cambia recién en la alarma $switchedAt (cada una pesa ${Tuner.ALARM_EVIDENCE})")
    require(switchedAt > 1) { "una sola alarma no debe cambiar los parámetros (switch en $switchedAt)" }
}

run {
    val s = segment("recorte", true, 0, noise(10, 0.1f))
    val n = s.size
    s.dropBefore(5_000L)
    require(s.startMs == 5_000L && s.size == n / 2 && Tuner.replay(s, Calibration.DEFAULT) == 0) { "dropBefore: ${s.startMs} ${s.size}" }
    s.dropBefore(0L)
    require(s.size == n / 2) { "dropBefore antes del inicio no hace nada" }
}

// 12) Registro de mañanas: CSV, horas en cama y resumen
require(CheckIn.hoursInBed(23 * 60 + 30, 7 * 60) == 7.5 && CheckIn.hoursInBed(60, 7 * 60) == 6.0) { "cruza la medianoche" }
run {
    val r = CheckIn("2026-10-16", "07:00", 1L, 3.25, "sentadillas", 9, 10, 1, true, false, "23:30", 7.5, null, "cambió: bajada 1,3 → 1,1")
    val back = CheckIn.parse(r.toCsv())!!
    require(back == r.copy(minutesToDismiss = 3.3, learned = "cambió: bajada 1;3 → 1;1")) { "ida y vuelta: $back" }
    require(CheckIn.parse(CheckIn.HEADER) == null && CheckIn.parse("basura") == null) { "encabezado y basura → null" }
    val blank = CheckIn("2026-10-17", "07:00", 2L, 1.0, "sensor", null, null, null, null, null, null, null, null, "")
    require(CheckIn.parse(blank.toCsv()) == blank) { "sin responder → null de vuelta" }
    val rows = listOf(
        r, r.copy(mood = -1, minutesToDismiss = 5.0), r.copy(alcohol = false, mood = 1, minutesToDismiss = 1.0, hoursInBed = 6.0),
        blank,
    )
    val alc = MorningSummary.factors(rows).first { it.name == "Alcohol" }
    require(alc.yes.n == 2 && near(alc.yes.mood!!, 0.0) && near(alc.yes.minutes!!, 4.125)) { "alcohol sí" }
    require(alc.no.n == 1 && alc.no.mood == 1.0 && alc.no.minutes == 1.0) { "alcohol no" }
    val sleep = MorningSummary.factors(rows).first { it.name.startsWith("7 h") }
    require(sleep.yes.n == 2 && sleep.no.n == 1) { "horas en cama" }
    require(MorningSummary.factors(rows).first { it.name == "Estrés" }.yes.n == 0) { "sin respuestas → grupo vacío" }
}

println("CORE OK")
tasks.register("coreTest") { doLast { println("coreTest: todos los asserts pasaron") } }
