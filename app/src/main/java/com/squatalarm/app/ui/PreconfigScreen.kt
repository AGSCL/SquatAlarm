package com.squatalarm.app.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.squatalarm.app.alarm.AlarmPrefs
import com.squatalarm.app.squat.BayesTuner
import com.squatalarm.app.squat.Calibration
import com.squatalarm.app.squat.Counts
import com.squatalarm.app.squat.Posterior
import com.squatalarm.app.squat.Segment
import com.squatalarm.app.squat.SquatCounter
import com.squatalarm.app.squat.SquatDetector
import com.squatalarm.app.squat.Tuner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class Phase { INTRO, READY, COUNTDOWN, RECORDING, ASK, UPDATING, RESULT, VAL_RESULT }

/** Un paso de grabación: serie de sentadillas (positivo) o prueba de falsación (negativo). */
private class RecStep(
    val name: String,
    val positive: Boolean,
    val instruction: String,
    val target: Int,
    val maxSeconds: Int,
    val validation: Boolean,
    val gif: String,
)

private const val SERIES_TEXT = "Haz 10 sentadillas completas a tu ritmo normal. Cuando termines, toca «Terminé»."

private val FALSIFICATIONS = listOf(
    RecStep("Falsación: quieto", false, "Quédate de pie, quieto. No debería contar nada.", 0, 20, false, "quieto"),
    RecStep("Falsación: caminar", false, "Marcha en el lugar levantando las rodillas, sin agacharte.", 0, 20, false, "caminar"),
    RecStep("Falsación: sacudir", false, "Sacude el teléfono arriba y abajo con fuerza, rápido.", 0, 20, false, "sacudir"),
    RecStep("Falsación: inclinarse", false, "Inclínate hacia adelante desde la cintura y vuelve, lento, con las rodillas rectas.", 0, 20, false, "inclinarse"),
    RecStep(
        "Falsación: solo brazos, lento", false,
        "Sin doblar las rodillas, baja el teléfono con los brazos hasta la cintura y súbelo al pecho, " +
            "al ritmo de una sentadilla y manteniéndolo vertical. Es la trampa más difícil de detectar.",
        0, 20, false, "brazos_lento",
    ),
    RecStep("Falsación: solo brazos, rápido", false, "Igual que la anterior, pero rápido y sin cuidar la posición del teléfono.", 0, 20, false, "brazos_rapido"),
)

/** Series intercaladas con falsaciones: contrabalancea la fatiga y el posterior aprende ambos lados desde el inicio. */
private fun trainingSteps(totalSquats: Int): List<RecStep> {
    val sets = totalSquats / 10
    val perGap = (FALSIFICATIONS.size + sets - 1) / sets
    val out = ArrayList<RecStep>()
    var f = 0
    for (s in 1..sets) {
        out += RecStep("Serie $s de $sets", true, SERIES_TEXT, 10, 150, false, "sentadilla")
        repeat(perGap) { if (f < FALSIFICATIONS.size) out += FALSIFICATIONS[f++] }
    }
    return out
}

private val VALIDATION_STEPS = listOf(
    RecStep("Validación: sentadillas", true, "Haz 10 sentadillas. Ahora la app usa los parámetros elegidos.", 10, 150, true, "sentadilla"),
    RecStep("Validación: caminar", false, "Camina en el lugar. No debería contar nada.", 0, 20, true, "caminar"),
    RecStep("Validación: solo brazos", false, "Baja y sube el teléfono solo con los brazos. No debería contar nada.", 0, 20, true, "brazos_lento"),
)

private class PreState {
    var phase by mutableStateOf(Phase.INTRO)
    var totalSquats by mutableIntStateOf(30)
    var weight by mutableFloatStateOf(0.5f)
    var steps: List<RecStep> = emptyList()
    var stepIndex by mutableIntStateOf(0)
    var countdown by mutableIntStateOf(5)
    var liveCount by mutableIntStateOf(0)
    var aVert by mutableFloatStateOf(0f)
    var elapsedS by mutableIntStateOf(0)
    var answer by mutableIntStateOf(0)
    var lastReject by mutableStateOf("")
    var recording = false
    var current: Segment? = null
    var tuner = BayesTuner()
    val validation = mutableListOf<Segment>()
    var tuned by mutableStateOf<Calibration?>(null)
    var tunedPost by mutableStateOf<Posterior?>(null)
    var baseline by mutableStateOf<Posterior?>(null)
    var valCounts by mutableStateOf<Counts?>(null)
    val step: RecStep get() = steps[stepIndex]
}

/**
 * Preconfiguración: series de 10 sentadillas intercaladas con 6 pruebas de falsación, cada paso
 * contrastado con lo que el usuario dice haber hecho. Tras cada paso el modelo bayesiano actualiza
 * sensibilidad y especificidad de todas las combinaciones de parámetros y el detector pasa a la mejor.
 * Al terminar se guarda solo; la validación prueba los parámetros con datos nuevos.
 */
@Composable
fun PreconfigScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AlarmPrefs(context) }
    val st = remember { PreState() }
    val scope = rememberCoroutineScope()
    val counter = remember { Tuner.PERMISSIVE.counter() }
    val detector = remember {
        SquatDetector(context, counter, object : SquatDetector.Listener {
            override fun onSquat(count: Int, squat: SquatCounter.Squat) {
                if (st.recording) st.liveCount = count
            }

            override fun onRejected(squat: SquatCounter.Squat) {
                if (st.recording) st.lastReject = rejectText(squat.reject)
            }

            override fun onSample(aVert: Float, state: SquatCounter.State) {
                st.aVert = aVert
            }
        }).also { d -> d.recorder = { a, t, x, y, z -> if (st.recording) st.current?.add(a, t, x, y, z) } }
    }
    val tone = remember { try { ToneGenerator(AudioManager.STREAM_ALARM, 80) } catch (e: Exception) { null } }
    val view = LocalView.current
    DisposableEffect(Unit) {
        // Con el teléfono contra el pecho, la pantalla se apagaría a mitad de un paso y se perderían muestras.
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            detector.stop()
            tone?.release()
        }
    }
    // Mientras graba (y justo al terminar, con el teléfono aún en el pecho), las manos tapan la pantalla:
    // un toque o gesto de Atrás accidental no debe borrar el progreso. Desde ASK se sale con «Confirmar».
    val busy = st.phase == Phase.COUNTDOWN || st.phase == Phase.RECORDING || st.phase == Phase.ASK || st.phase == Phase.UPDATING
    BackHandler(enabled = busy) {}

    // Pitido + vibración: con el teléfono contra el pecho no se ve la pantalla.
    fun cue() {
        try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 250) } catch (e: Exception) { }
        try { vibrator(context)?.vibrate(VibrationEffect.createOneShot(200L, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (e: Exception) { }
    }

    fun startSteps(steps: List<RecStep>) {
        st.steps = steps
        st.stepIndex = 0
        st.phase = Phase.READY
    }

    fun finishRecording() {
        st.recording = false
        detector.stop()
        st.current?.liveCount = st.liveCount
        st.answer = st.step.target
        cue()
        st.phase = Phase.ASK
    }

    fun save(c: Calibration, validation: Posterior?) {
        prefs.calibration = c.copy(
            valSens = validation?.sens?.toFloat() ?: -1f,
            valSpec = validation?.spec?.toFloat() ?: -1f,
        )
        prefs.strictness = 1f
    }

    LaunchedEffect(st.phase) {
        when (st.phase) {
            Phase.INTRO -> {
                detector.stop()
                if (st.tuner.segments.isNotEmpty()) st.tuner = BayesTuner()
                st.validation.clear()
                st.tuned = null; st.tunedPost = null; st.baseline = null; st.valCounts = null
            }
            Phase.COUNTDOWN -> {
                val step = st.step
                val params = when {
                    step.validation -> st.tuned ?: Tuner.PERMISSIVE
                    st.tuner.history.isNotEmpty() -> st.tuner.history.last().calibration
                    else -> Tuner.PERMISSIVE
                }
                params.applyTo(counter)
                detector.start() // los sensores arrancan ya: la gravedad se estabiliza durante la cuenta
                for (i in 5 downTo 1) {
                    st.countdown = i
                    delay(1000L)
                }
                st.current = Segment(step.name, step.positive)
                counter.reset()
                st.liveCount = 0
                st.lastReject = ""
                st.elapsedS = 0
                st.recording = true
                cue()
                st.phase = Phase.RECORDING
            }
            Phase.RECORDING -> {
                while (st.elapsedS < st.step.maxSeconds) {
                    delay(1000L)
                    st.elapsedS++
                }
                finishRecording()
            }
            Phase.UPDATING -> {
                val seg = st.current ?: return@LaunchedEffect
                val w = st.weight.toDouble()
                val last = st.stepIndex + 1 >= st.steps.size
                val result = withContext(Dispatchers.Default) {
                    st.tuner.add(seg, w)
                    if (last) {
                        val (cal, post) = st.tuner.best(w)
                        Triple(cal, post, Tuner.evaluate(st.tuner.segments, Calibration.DEFAULT, st.tuner.windowMs).posterior())
                    } else {
                        null
                    }
                }
                if (result != null) {
                    st.tuned = result.first
                    st.tunedPost = result.second
                    st.baseline = result.third
                    save(result.first, null) // se guarda solo: salir con «Volver» ya no pierde la preconfiguración
                    st.phase = Phase.RESULT
                } else {
                    st.stepIndex++
                    st.phase = Phase.READY
                }
            }
            Phase.VAL_RESULT -> {
                val t = st.tuned ?: return@LaunchedEffect
                val c = Tuner.evaluate(st.validation.toList(), t, st.tuner.windowMs)
                st.valCounts = c
                save(t, c.posterior())
            }
            else -> {}
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader("Preconfigurar detección", onBack = onDone.takeUnless { busy })
            when (st.phase) {
                Phase.INTRO -> Intro(st, detector.isAvailable) { startSteps(trainingSteps(st.totalSquats)) }
                Phase.READY -> Ready(st) { st.phase = Phase.COUNTDOWN }
                Phase.COUNTDOWN -> {
                    Text(st.step.name, style = MaterialTheme.typography.titleLarge)
                    BigNumber("${st.countdown}")
                    Text(
                        "Ponte en posición con el teléfono contra el pecho. Empieza con el pitido.",
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                }
                Phase.RECORDING -> Recording(st, counter) { finishRecording() }
                Phase.ASK -> Ask(
                    st,
                    onConfirm = {
                        val seg = st.current
                        if (seg != null) {
                            seg.truth = st.answer
                            if (st.step.validation) {
                                st.validation.add(seg)
                                if (st.stepIndex + 1 < st.steps.size) {
                                    st.stepIndex++
                                    st.phase = Phase.READY
                                } else {
                                    st.phase = Phase.VAL_RESULT
                                }
                            } else {
                                st.phase = Phase.UPDATING
                            }
                        }
                    },
                    onRedo = { st.phase = Phase.COUNTDOWN },
                )
                Phase.UPDATING -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text(
                        "Actualizando el modelo bayesiano con este paso (${BayesTuner.N} combinaciones de parámetros)…",
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                }
                Phase.RESULT -> TuneResult(
                    st,
                    onWeightChanged = {
                        scope.launch {
                            val (cal, post) = withContext(Dispatchers.Default) { st.tuner.best(st.weight.toDouble()) }
                            st.tuned = cal; st.tunedPost = post; st.valCounts = null
                            save(cal, null)
                        }
                    },
                    onValidate = { st.validation.clear(); st.valCounts = null; startSteps(VALIDATION_STEPS) },
                    onDone = onDone,
                    onRestart = { st.phase = Phase.INTRO },
                )
                Phase.VAL_RESULT -> ValidationResult(
                    st,
                    onDone = onDone,
                    onBack = { st.phase = Phase.RESULT },
                    onRestart = { st.phase = Phase.INTRO },
                )
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun vibrator(context: Context): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
} else {
    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
}

@Composable
private fun ColumnScope.Intro(st: PreState, sensorAvailable: Boolean, onStart: () -> Unit) {
    GuideGif("sentadilla")
    Text(
        "Esta fase mide cómo se mueve tu teléfono cuando haces sentadillas y cuando NO las haces, y ajusta la detección a ti.\n\n" +
            "• Sostén el teléfono contra el pecho con las dos manos, pantalla hacia afuera: al bajar, tu torso se inclina y eso lo distingue de mover el teléfono con los brazos.\n" +
            "• ${st.totalSquats / 10} series de 10 sentadillas intercaladas con 6 pruebas de falsación de 20 s (quieto, caminar, sacudir, inclinarse y dos de solo brazos). Cada paso trae un GIF de la postura.\n" +
            "• Tras cada paso te preguntamos qué hiciste realmente y el modelo bayesiano actualiza la sensibilidad y especificidad de ${BayesTuner.N} combinaciones de parámetros; el detector pasa a usar la mejor.\n" +
            "• Al final se guarda solo, y puedes validarlo con datos nuevos."
    )
    Text("Sentadillas de entrenamiento: ${st.totalSquats} (${st.totalSquats / 10} series)")
    Slider(
        value = st.totalSquats.toFloat(),
        onValueChange = { st.totalSquats = (it / 10f).roundToInt() * 10 },
        valueRange = 30f..60f,
        steps = 2,
        modifier = Modifier.labeled("Sentadillas de entrenamiento", "${st.totalSquats}"),
    )
    if (!sensorAvailable) {
        Text("⚠ Este teléfono no tiene acelerómetro; no se puede preconfigurar.", color = MaterialTheme.colorScheme.error)
    }
    Button(onClick = onStart, enabled = sensorAvailable, modifier = Modifier.fillMaxWidth()) { Text("Comenzar") }
}

@Composable
private fun ColumnScope.Ready(st: PreState, onStart: () -> Unit) {
    val step = st.step
    Text("Paso ${st.stepIndex + 1} de ${st.steps.size}", style = MaterialTheme.typography.labelLarge)
    Text(step.name, style = MaterialTheme.typography.titleLarge)
    GuideGif(step.gif)
    Text(step.instruction)
    Text(
        "Teléfono contra el pecho con las dos manos, pantalla hacia afuera. Sonará un pitido al empezar" +
            (if (step.positive) "." else " y otro a los ${step.maxSeconds} s, al terminar."),
        style = MaterialTheme.typography.bodySmall,
    )
    val snap = st.tuner.history.lastOrNull()
    if (snap != null && !step.validation) {
        SectionCard("Lo aprendido hasta ahora (${st.tuner.history.size} pasos)") {
            PosteriorLines(snap.posterior)
            Text("El detector usará: " + vetoSummary(snap.calibration), style = MaterialTheme.typography.bodySmall)
        }
    }
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Comenzar este paso") }
}

@Composable
private fun ColumnScope.Recording(st: PreState, counter: SquatCounter, onFinish: () -> Unit) {
    val step = st.step
    Text(step.name, style = MaterialTheme.typography.titleLarge)
    Text(
        if (step.positive) "Contadas por la app" else "Detecciones (deberían ser 0)",
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
    )
    BigNumber("${st.liveCount}", color = if (step.positive || st.liveCount == 0) GREEN else RED)
    LiveBar(st.aVert, counter.thresholdDown, counter.thresholdUp)
    if (st.lastReject.isNotEmpty()) {
        Text(st.lastReject, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    if (step.positive) {
        Text("Tiempo: ${st.elapsedS} s", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Terminé") }
    } else {
        LinearProgressIndicator(
            progress = { st.elapsedS / step.maxSeconds.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Quedan ${step.maxSeconds - st.elapsedS} s", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ColumnScope.Ask(st: PreState, onConfirm: () -> Unit, onRedo: () -> Unit) {
    val step = st.step
    Text(step.name, style = MaterialTheme.typography.titleLarge)
    Text("La app contó ${st.liveCount}.", style = MaterialTheme.typography.titleMedium)
    Text(
        if (step.positive) "¿Cuántas sentadillas reales hiciste en esta serie?"
        else "¿Hiciste alguna sentadilla real durante esta prueba? (normalmente 0)"
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { if (st.answer > 0) st.answer-- }, Modifier.semantics { contentDescription = "Una menos" }) { Text("−") }
        Text(
            "${st.answer}",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        OutlinedButton(onClick = { if (st.answer < 60) st.answer++ }, Modifier.semantics { contentDescription = "Una más" }) { Text("+") }
    }
    if (st.liveCount != st.answer) {
        Text(
            if (st.liveCount > st.answer) "La app contó de más: se registrarán ${st.liveCount - st.answer} falsos positivos."
            else "La app contó de menos: se registrarán ${st.answer - st.liveCount} sentadillas no detectadas.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("Confirmar") }
    TextButton(onClick = onRedo, modifier = Modifier.fillMaxWidth()) { Text("Repetir este paso") }
}

/** Media posterior e intervalo de credibilidad 95 % (colas iguales) de sensibilidad y especificidad. */
@Composable
private fun PosteriorLines(p: Posterior) {
    val (sLo, sHi) = p.sensCrI()
    val tp = (p.aS - 1).roundToInt()
    val nPos = (p.aS + p.bS - 2).roundToInt()
    Text("Sensibilidad ${pct(p.sens)} (ICr 95 % ${pct(sLo)}–${pct(sHi)}; $tp/$nPos sentadillas)")
    if (p.hasNeg) {
        val (eLo, eHi) = p.specCrI()
        val tn = (p.aE - 1).roundToInt()
        val nNeg = (p.aE + p.bE - 2).roundToInt()
        Text("Especificidad ${pct(p.spec)} (ICr 95 % ${pct(eLo)}–${pct(eHi)}; $tn/$nNeg ventanas)")
    } else {
        Text("Especificidad: aún sin datos negativos (previa uniforme)")
    }
}

@Composable
private fun ColumnScope.TuneResult(st: PreState, onWeightChanged: () -> Unit, onValidate: () -> Unit, onDone: () -> Unit, onRestart: () -> Unit) {
    val tuned = st.tuned
    val post = st.tunedPost
    Text("Resultado bayesiano", style = MaterialTheme.typography.titleLarge)
    if (tuned == null || post == null) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        return
    }
    Text("✓ Guardado. Ya puedes usar la alarma; validar es opcional pero recomendable.", color = GREEN)
    SectionCard("Posterior con tus datos de entrenamiento") {
        PosteriorLines(post)
        st.baseline?.let {
            Text("Sin preconfigurar (valores por defecto), mismos datos:", style = MaterialTheme.typography.labelLarge)
            PosteriorLines(it)
        }
        Text(
            "Previa uniforme Beta(1,1) para cada combinación de parámetros; el detector usa la de mayor utilidad esperada " +
                "a posteriori. Como se eligió con estos mismos datos, la cifra es optimista: la validación la pone a prueba con datos nuevos.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    SectionCard("Prioridad del ajuste") {
        Text("Peso de la sensibilidad: %.2f  (especificidad: %.2f)".format(st.weight, 1f - st.weight))
        Slider(
            value = st.weight,
            onValueChange = { st.weight = (it * 20f).roundToInt() / 20f },
            onValueChangeFinished = onWeightChanged,
            valueRange = 0.1f..0.9f,
            modifier = Modifier.labeled("Peso de la sensibilidad", "%.2f".format(st.weight)),
        )
        Text("← que no cuente trampas      que no se le escapen sentadillas →", style = MaterialTheme.typography.bodySmall)
    }
    SectionCard("Parámetros elegidos") {
        Text(vetoSummary(tuned))
    }
    SectionCard("Aprendizaje paso a paso") {
        st.tuner.history.forEachIndexed { i, s ->
            val p = s.posterior
            val spec = if (p.hasNeg) pct(p.spec) else "—"
            Text("${i + 1}. ${s.step}: sens ${pct(p.sens)} · esp $spec", style = MaterialTheme.typography.bodySmall)
        }
    }
    val perStep = remember(tuned) { st.tuner.segments.map { Triple(it.name, it.truth, Tuner.replay(it, tuned)) } }
    SectionCard("Por paso (reales → contadas con los parámetros elegidos)") {
        perStep.forEach { (name, truth, counted) ->
            val ok = counted == truth
            Text("${if (ok) "✓" else "✗"} $name: $truth → $counted", color = if (ok) GREEN else RED)
        }
    }
    Button(onClick = onValidate, modifier = Modifier.fillMaxWidth()) { Text("Validar con datos nuevos") }
    OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Listo") }
    TextButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text("Repetir todo") }
}

@Composable
private fun ColumnScope.ValidationResult(st: PreState, onDone: () -> Unit, onBack: () -> Unit, onRestart: () -> Unit) {
    val c = st.valCounts
    Text("Validación", style = MaterialTheme.typography.titleLarge)
    if (c == null) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        return
    }
    val p = c.posterior()
    val passed = p.sens >= Tuner.VALIDATION_MIN_SENS && p.spec >= Tuner.VALIDATION_MIN_SPEC
    SectionCard(if (passed) "✓ Validación superada" else "✗ Validación insuficiente") {
        PosteriorLines(p)
        Text(
            "Probabilidad posterior de sensibilidad ≥ ${pct(Tuner.VALIDATION_MIN_SENS)}: ${pct(p.probSensAtLeast(Tuner.VALIDATION_MIN_SENS))} · " +
                "de especificidad ≥ ${pct(Tuner.VALIDATION_MIN_SPEC)}: ${pct(p.probSpecAtLeast(Tuner.VALIDATION_MIN_SPEC))}",
            style = MaterialTheme.typography.bodySmall,
        )
        st.validation.forEach { seg -> Text("${seg.name}: ${seg.truth} reales → ${seg.liveCount} contadas") }
        Text(
            if (passed) "La detección funciona con datos que no se usaron para ajustarla. Quedó guardada con estas métricas."
            else "Mínimos: sensibilidad ${pct(Tuner.VALIDATION_MIN_SENS)} y especificidad ${pct(Tuner.VALIDATION_MIN_SPEC)} (medias posteriores). " +
                "Cambia la prioridad del ajuste o repite todo haciendo el movimiento más marcado.",
        )
    }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Listo") }
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cambiar prioridad del ajuste") }
    TextButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text("Repetir todo") }
}

internal fun pct(v: Float): String = "${(v * 100f).roundToInt()} %"
internal fun pct(v: Double): String = "${(v * 100.0).roundToInt()} %"
