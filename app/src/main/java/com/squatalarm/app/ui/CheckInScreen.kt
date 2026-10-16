package com.squatalarm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.squatalarm.app.alarm.CheckIn

/** Respuestas del registro de la mañana; null = sin responder. */
class Answers(
    val real: Int?,
    val mood: Int?,
    val alcohol: Boolean?,
    val smoked: Boolean?,
    val bedMin: Int?,
    val stressed: Boolean?,
)

/**
 * Registro al apagar una alarma real: una sola pantalla, un toque por pregunta (otro toque la desmarca).
 * [result] != null: ya se guardó y se muestra qué aprendió.
 */
@Composable
fun CheckInScreen(
    askSquats: Boolean,
    counted: Int,
    defaultBedMin: Int,
    saving: Boolean,
    result: String?,
    onSave: (Answers) -> Unit,
    onSkip: () -> Unit,
    onClose: () -> Unit,
) {
    var real by remember { mutableIntStateOf(counted) }
    var mood by remember { mutableStateOf<Int?>(null) }
    var alcohol by remember { mutableStateOf<Boolean?>(null) }
    var smoked by remember { mutableStateOf<Boolean?>(null) }
    var bedMin by remember { mutableIntStateOf(defaultBedMin) }
    var stressed by remember { mutableStateOf<Boolean?>(null) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (result != null) {
                Text("✓ Listo", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(result, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Cerrar") }
                return@Column
            }
            Text("¿Cómo amaneciste?", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text("Un toque por pregunta; lo que no respondas queda en blanco.", style = MaterialTheme.typography.bodySmall)

            if (askSquats) {
                Question("Sentadillas que hiciste de verdad") {
                    Stepper(
                        text = "$real", less = "Una menos", more = "Una más",
                        onLess = { if (real > 0) real-- }, onMore = { if (real < 60) real++ },
                    )
                    Text("La app contó $counted. Si no coincide, así aprende la detección.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Question("¿Cómo te sientes?") {
                Choice(listOf("🙂 Bien" to 1, "😐 Normal" to 0, "☹️ Mal" to -1), mood) { mood = it }
            }
            Question("¿Tomaste alcohol ayer?") { Choice(YES_NO, alcohol) { alcohol = it } }
            Question("¿Fumaste ayer?") { Choice(YES_NO, smoked) { smoked = it } }
            Question("¿A qué hora te acostaste?") {
                Stepper(
                    text = CheckIn.hhmm(bedMin), less = "15 minutos antes", more = "15 minutos después",
                    onLess = { bedMin = (bedMin - 15 + 1440) % 1440 }, onMore = { bedMin = (bedMin + 15) % 1440 },
                )
            }
            Question("¿Te acostaste con estrés?") { Choice(YES_NO, stressed) { stressed = it } }

            Button(
                onClick = { onSave(Answers(if (askSquats) real else null, mood, alcohol, smoked, bedMin, stressed)) },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(if (saving) "Guardando y aprendiendo…" else "Guardar") }
            TextButton(onClick = onSkip, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Omitir") }
        }
    }
}

private val YES_NO = listOf("Sí" to true, "No" to false)

@Composable
private fun Question(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

/** Opciones grandes de un toque; tocar la elegida la desmarca. */
@Composable
private fun <T> Choice(options: List<Pair<String, T>>, selected: T?, onSelect: (T?) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(if (selected == value) null else value) },
                label = { Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            )
        }
    }
}

@Composable
private fun Stepper(text: String, less: String, more: String, onLess: () -> Unit, onMore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onLess, modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = less }) { Text("−") }
        Text(
            text,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(horizontal = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        OutlinedButton(onClick = onMore, modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = more }) { Text("+") }
    }
}
