package com.squatalarm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.squatalarm.app.alarm.AlarmPrefs
import com.squatalarm.app.squat.SquatCounter
import com.squatalarm.app.squat.SquatDetector
import kotlin.math.roundToInt

private class TestState(strictness: Float) {
    var count by mutableIntStateOf(0)
    var aVert by mutableFloatStateOf(0f)
    var strictness by mutableFloatStateOf(strictness)
    var lastInfo by mutableStateOf("")
}

private fun cycleInfo(s: SquatCounter.Squat) =
    "bajada %.1f · subida %.1f m/s² · %d ms · inclinación %.0f°".format(s.negPeak, s.posPeak, s.cycleMs, s.tiltDeg)

/** Prueba rápida con los mismos umbrales que usará la alarma (calibración × exigencia). */
@Composable
fun TestScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AlarmPrefs(context) }
    val calibration = remember { prefs.calibration }
    val st = remember { TestState(prefs.strictness) }
    val detector = remember {
        SquatDetector.fromPrefs(context, prefs, object : SquatDetector.Listener {
            override fun onSquat(count: Int, squat: SquatCounter.Squat) {
                st.count = count
                st.lastInfo = "✓ Última: " + cycleInfo(squat)
            }

            override fun onRejected(squat: SquatCounter.Squat) {
                st.lastInfo = rejectText(squat.reject) + "\n" + cycleInfo(squat)
            }

            override fun onSample(aVert: Float, state: SquatCounter.State) {
                st.aVert = aVert
            }
        })
    }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true // con el teléfono contra el pecho, la pantalla se apagaría a media prueba
        detector.start()
        onDispose {
            view.keepScreenOn = false
            detector.stop()
        }
    }

    fun applyStrictness(s: Float) {
        st.strictness = s
        prefs.strictness = s
        detector.counter.thresholdDown = calibration.thresholdDown * s
        detector.counter.thresholdUp = calibration.thresholdUp * s
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
            ScreenHeader("Probar detección", onBack = onDone)
            Text(
                if (calibration.calibrated) "Usando tu preconfiguración. Haz sentadillas: cada una debe sumar 1."
                else "Sin preconfigurar: se usan valores por defecto. Mejor preconfigura primero.",
                color = if (calibration.calibrated) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
            Text(vetoSummary(calibration), style = MaterialTheme.typography.bodySmall)
            if (!detector.isAvailable) {
                Text("⚠ Este teléfono no tiene acelerómetro.", color = MaterialTheme.colorScheme.error)
            }
            BigNumber("${st.count}", color = GREEN)
            LiveBar(st.aVert, detector.counter.thresholdDown, detector.counter.thresholdUp)
            Text(st.lastInfo, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

            Text("Exigencia del movimiento: ×%.1f (menos = cuenta más fácil)".format(st.strictness))
            Slider(
                value = st.strictness,
                onValueChange = { applyStrictness((it * 10f).roundToInt() / 10f) },
                valueRange = 0.5f..1.8f,
                modifier = Modifier.labeled("Exigencia del movimiento", "×%.1f".format(st.strictness)),
            )
            Text(
                "Si cuenta de más (sacudidas, pasos): sube la exigencia. Si no cuenta sentadillas reales: bájala o vuelve a preconfigurar.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { detector.counter.reset(); st.count = 0; st.lastInfo = "" },
                    modifier = Modifier.weight(1f),
                ) { Text("Reiniciar contador") }
                Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Listo") }
            }
        }
    }
}
