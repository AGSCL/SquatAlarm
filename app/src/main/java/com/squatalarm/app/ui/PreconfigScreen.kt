package com.squatalarm.app.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.squatalarm.app.alarm.AlarmPrefs
import com.squatalarm.app.squat.Calibration
import com.squatalarm.app.squat.Metrics
import com.squatalarm.app.squat.Segment
import com.squatalarm.app.squat.SquatCounter
import com.squatalarm.app.squat.SquatDetector
import com.squatalarm.app.squat.Tuner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class Phase { INTRO, COUNTDOWN, RECORDING, ASK, TUNING, RESULT, VAL_RESULT }

/** Un paso de grabación: serie de sentadillas (positivo) o prueba de falsación (negativo). */
private class RecStep(
    val name: String,
    val positive: Boolean,
    val instruction: String,
    val target: Int,
    val maxSeconds: Int,
    val validation: Boolean,
)

private fun trainingSteps(totalSquats: Int): List<RecStep> {
    val sets = totalSquats / 10
    val pos = (1..sets).map {
        RecStep("Serie $it de $sets", true, "Haz 10 sentadillas completas a tu ritmo normal. Cuando termines, toca «Terminé».", 10, 150, false)
    }
    val neg = listOf(
        RecStep("Falsación 1: quieto", false, "Quédate de pie, quieto, con el teléfono contra el pecho. No debería contar nada.", 0, 20, false),
        RecStep("Falsación 2: caminar", false, "Camina en el lugar (marcha enérgica) sin agacharte.", 0, 20, false),
        RecStep("Falsación 3: sacudir", false, "Sacude el teléfono arriba y abajo con fuerza, rápido.", 0, 20, false),
        RecStep("Falsación 4: inclinarse", false, "Inclínate hacia adelante desde la cintura y vuelve, lento, con las rodillas rectas.", 0, 20, false),
    )
    return pos + neg
}

private val VALIDATION_STEPS = listOf(
    RecStep("Validación 1: sentadillas", true, "Haz 10 sentadillas. Ahora la app usa los umbrales ajustados.", 10, 150, true),
    RecStep("Validación 2: caminar", false, "Camina en el lugar. No debería contar nada.", 0, 20, true),
)

private class PreState {
    var phase by mutableStateOf(Phase.INTRO)
    var totalSquats by mutableIntStateOf(30)
    var weight by mutableFloatStateOf(0.5f)
    var steps: List<RecStep> = emptyList()
    var stepIndex by mutableIntStateOf(0)
    var countdown by mutableIntStateOf(3)
    var liveCount by mutableIntStateOf(0)
    var aVert by mutableFloatStateOf(0f)
    var elapsedS by mutableIntStateOf(0)
    var answer by mutableIntStateOf(0)
    var recording = false
    var current: Segment? = null
    val training = mutableListOf<Segment>()
    val validation = mutableListOf<Segment>()
    var baseline by mutableStateOf<Metrics?>(null)
    var tuned by mutableStateOf<Calibration?>(null)
    var tunedMetrics by mutableStateOf<Metrics?>(null)
    var valMetrics by mutableStateOf<Metrics?>(null)
    var tuning by mutableStateOf(false)
    val step: RecStep get() = steps[stepIndex]
}

/**
 * Preconfiguración: ≥30 sentadillas en series de 10 + 4 pruebas de falsación, cada paso contrastado
 * con lo que el usuario dice haber hecho; luego ajuste automático de umbrales (sens/esp) y validación.
 */
@Composable
fun PreconfigScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AlarmPrefs(context) }
    val st = remember { PreState() }
    val scope = rememberCoroutineScope()
    val counter = remember { SquatCounter(Tuner.PERMISSIVE.thresholdDown, Tuner.PERMISSIVE.thresholdUp, Tuner.PERMISSIVE.minCycleMs, Tuner.PERMISSIVE.maxCycleMs) }
    val detector = remember {
        SquatDetector(context, counter, object : SquatDetector.Listener {
            override fun onSquat(count: Int, squat: SquatCounter.Squat) {
                if (st.recording) st.liveCount = count
            }

            override fun onSample(aVert: Float, state: SquatCounter.State) {
                st.aVert = aVert
            }
        }).also { d -> d.recorder = { a, t -> if (st.recording) st.current?.add(a, t) } }
    }
    DisposableEffect(Unit) { onDispose { detector.stop() } }

    fun applyParams(c: Calibration) {
        counter.thresholdDown = c.thresholdDown
        counter.thresholdUp = c.thresholdUp
        counter.minCycleMs = c.minCycleMs
        counter.maxCycleMs = c.maxCycleMs
    }

    fun startSteps(steps: List<RecStep>) {
        st.steps = steps
        st.stepIndex = 0
        st.phase = Phase.COUNTDOWN
    }

    fun finishRecording() {
        st.recording = false
        detector.stop()
        st.current?.liveCount = st.liveCount
        st.answer = st.step.target
        st.phase = Phase.ASK
    }

    suspend fun runTune() {
        st.tuning = true
        val data = st.training.toList()
        val w = st.weight
        val (base, result) = withContext(Dispatchers.Default) {
            Tuner.evaluate(data, Calibration.DEFAULT) to Tuner.tune(data, w)
        }
        st.baseline = base
        st.tuned = result.first
        st.tunedMetrics = result.second
        st.tuning = false
    }

    fun save() {
        val t = st.tuned ?: return
        val v = st.valMetrics
        prefs.calibration = if (v == null) t else t.copy(valSens = v.sens, valSpec = v.spec)
        prefs.strictness = 1f
        onDone()
    }

    LaunchedEffect(st.phase) {
        when (st.phase) {
            Phase.INTRO -> {
                detector.stop()
                st.training.clear(); st.validation.clear()
                st.tuned = null; st.tunedMetrics = null; st.baseline = null; st.valMetrics = null
            }
            Phase.COUNTDOWN -> {
                val step = st.step
                applyParams(if (step.validation) st.tuned ?: Tuner.PERMISSIVE else Tuner.PERMISSIVE)
                detector.start() // los sensores arrancan ya, así la gravedad se estabiliza durante la cuenta
                for (i in 3 downTo 1) {
                    st.countdown = i
                    delay(1000L)
                }
                st.current = Segment(step.name, step.positive)
                counter.reset()
                st.liveCount = 0
                st.elapsedS = 0
                st.recording = true
                st.phase = Phase.RECORDING
            }
            Phase.RECORDING -> {
                while (st.elapsedS < st.step.maxSeconds) {
                    delay(1000L)
                    st.elapsedS++
                }
                finishRecording()
            }
            Phase.ASK -> {}
            Phase.TUNING -> {
                runTune()
                st.phase = Phase.RESULT
            }
            Phase.RESULT -> {}
            Phase.VAL_RESULT -> {
                val t = st.tuned ?: return@LaunchedEffect
                st.valMetrics = Tuner.evaluate(st.validation.toList(), t)
            }
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
            ScreenHeader("Preconfigurar detección", onBack = onDone)
            when (st.phase) {
                Phase.INTRO -> Intro(st, detector.isAvailable) { startSteps(trainingSteps(st.totalSquats)) }
                Phase.COUNTDOWN -> {
                    Text(st.step.name, style = MaterialTheme.typography.titleLarge)
                    Text(st.step.instruction)
                    BigNumber("${st.countdown}")
                    Text("Prepárate…", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                Phase.RECORDING -> Recording(st, counter) { finishRecording() }
                Phase.ASK -> Ask(
                    st,
                    onConfirm = {
                        st.current?.let { seg ->
                            seg.truth = st.answer
                            if (st.step.validation) st.validation.add(seg) else st.training.add(seg)
                        }
                        when {
                            st.stepIndex + 1 < st.steps.size -> { st.stepIndex++; st.phase = Phase.COUNTDOWN }
                            st.step.validation -> st.phase = Phase.VAL_RESULT
                            else -> st.phase = Phase.TUNING
                        }
                    },
                    onRedo = { st.phase = Phase.COUNTDOWN },
                )
                Phase.TUNING -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text("Ajustando umbrales (búsqueda en rejilla sobre lo grabado)…", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                Phase.RESULT -> TuneResult(
                    st,
                    onWeightChanged = { scope.launch { runTune() } },
                    onValidate = { st.validation.clear(); st.valMetrics = null; startSteps(VALIDATION_STEPS) },
                    onSave = { save() },
                    onRestart = { st.phase = Phase.INTRO },
                )
                Phase.VAL_RESULT -> ValidationResult(
                    st,
                    onSave = { save() },
                    onBack = { st.phase = Phase.RESULT },
                    onRestart = { st.phase = Phase.INTRO },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.Intro(st: PreState, sensorAvailable: Boolean, onStart: () -> Unit) {
    Text(
        "Esta fase mide cómo se mueve tu teléfono cuando haces sentadillas y cuando NO las haces, " +
            "y ajusta la detección a ti.\n\n" +
            "1. Sostén el teléfono contra el pecho con las dos manos, pantalla hacia afuera.\n" +
            "2. Series de 10 sentadillas (${st.totalSquats} en total). Tras cada serie te preguntamos cuántas hiciste realmente.\n" +
            "3. Cuatro pruebas de falsación de 20 s (quieto, caminar, sacudir, inclinarse): nada de eso debería contar.\n" +
            "4. La app busca los umbrales que maximizan sensibilidad y especificidad, y los validas con una serie extra."
    )
    Text("Sentadillas de entrenamiento: ${st.totalSquats} (${st.totalSquats / 10} series)")
    Slider(
        value = st.totalSquats.toFloat(),
        onValueChange = { st.totalSquats = (it / 10f).roundToInt() * 10 },
        valueRange = 30f..60f,
        steps = 2,
    )
    if (!sensorAvailable) {
        Text("⚠ Este teléfono no tiene acelerómetro; no se puede preconfigurar.", color = MaterialTheme.colorScheme.error)
    }
    Button(onClick = onStart, enabled = sensorAvailable, modifier = Modifier.fillMaxWidth()) { Text("Comenzar") }
}

@Composable
private fun ColumnScope.Recording(st: PreState, counter: SquatCounter, onFinish: () -> Unit) {
    val step = st.step
    Text(step.name, style = MaterialTheme.typography.titleLarge)
    Text(step.instruction)
    Text(
        if (step.positive) "Contadas por la app" else "Detecciones (deberían ser 0)",
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
    )
    BigNumber("${st.liveCount}", color = if (step.positive || st.liveCount == 0) GREEN else RED)
    LiveBar(st.aVert, counter.thresholdDown, counter.thresholdUp)
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
        OutlinedButton(onClick = { if (st.answer > 0) st.answer-- }) { Text("−") }
        Text(
            "${st.answer}",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        OutlinedButton(onClick = { if (st.answer < 60) st.answer++ }) { Text("+") }
    }
    if (step.positive && st.liveCount != st.answer) {
        Text(
            if (st.liveCount > st.answer) "La app contó de más: se registrarán ${st.liveCount - st.answer} falsos positivos."
            else "La app contó de menos: se registrarán ${st.answer - st.liveCount} sentadillas no detectadas.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("Confirmar") }
    TextButton(onClick = onRedo, modifier = Modifier.fillMaxWidth()) { Text("Repetir este paso") }
}

@Composable
private fun MetricsText(label: String, m: Metrics) {
    Text(
        "$label: sensibilidad ${pct(m.sens)} (${m.tp}/${m.nPos}) · especificidad ${pct(m.spec)} (${m.tn}/${m.nNeg})",
    )
}

@Composable
private fun ColumnScope.TuneResult(st: PreState, onWeightChanged: () -> Unit, onValidate: () -> Unit, onSave: () -> Unit, onRestart: () -> Unit) {
    val tuned = st.tuned
    val m = st.tunedMetrics
    Text("Resultado del ajuste", style = MaterialTheme.typography.titleLarge)
    if (st.tuning || tuned == null || m == null) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        return
    }
    SectionCard("Métricas") {
        st.baseline?.let { MetricsText("Sin calibrar", it) }
        MetricsText("Ajustado", m)
        Text(
            "Sensibilidad = sentadillas reales detectadas / reales. Especificidad = ventanas sin sentadilla " +
                "(del largo de una sentadilla tuya) sin falsa detección / total de esas ventanas.",
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
        )
        Text("← no contar de más (más exigente)      no contar de menos (más permisivo) →", style = MaterialTheme.typography.bodySmall)
    }
    SectionCard("Umbrales elegidos") {
        Text("Bajada %.1f · subida %.1f m/s² · mínimo bajada→subida %d ms · máximo %d ms".format(
            tuned.thresholdDown, tuned.thresholdUp, tuned.minCycleMs, tuned.maxCycleMs
        ))
    }
    SectionCard("Por paso (reales → contadas con los umbrales ajustados)") {
        st.training.forEach { seg ->
            val replayed = Tuner.replay(seg, tuned)
            val ok = replayed == seg.truth
            Text("${if (ok) "✓" else "✗"} ${seg.name}: ${seg.truth} → $replayed", color = if (ok) GREEN else RED)
        }
    }
    Button(onClick = onValidate, modifier = Modifier.fillMaxWidth()) { Text("Validar (10 sentadillas + caminar)") }
    OutlinedButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Guardar sin validar") }
    TextButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text("Repetir todo") }
}

@Composable
private fun ColumnScope.ValidationResult(st: PreState, onSave: () -> Unit, onBack: () -> Unit, onRestart: () -> Unit) {
    val m = st.valMetrics
    Text("Validación", style = MaterialTheme.typography.titleLarge)
    if (m == null) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        return
    }
    val passed = m.sens >= Tuner.VALIDATION_MIN_SENS && m.spec >= Tuner.VALIDATION_MIN_SPEC
    SectionCard(if (passed) "✓ Validación superada" else "✗ Validación insuficiente") {
        MetricsText("Con datos nuevos", m)
        st.validation.forEach { seg -> Text("${seg.name}: ${seg.truth} reales → ${seg.liveCount} contadas") }
        Text(
            if (passed) "La detección funciona con datos que no se usaron para ajustarla. Puedes guardar."
            else "Mínimos: sensibilidad ${pct(Tuner.VALIDATION_MIN_SENS)} y especificidad ${pct(Tuner.VALIDATION_MIN_SPEC)}. " +
                "Cambia la prioridad del ajuste o repite todo haciendo el movimiento más marcado.",
        )
    }
    Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text(if (passed) "Guardar" else "Guardar de todos modos") }
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cambiar prioridad del ajuste") }
    TextButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text("Repetir todo") }
}

internal fun pct(v: Float): String = "${(v * 100f).roundToInt()} %"
