package com.squatalarm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AlarmScreen(
    required: Int,
    done: Int,
    aVert: Float,
    thresholdDown: Float,
    thresholdUp: Float,
    sensorAvailable: Boolean,
    rejection: String,
    /** Misión «ir hasta el sensor»: texto de la notificación esperada; null = sentadillas. */
    sensorText: String?,
    showEscape: Boolean,
    onEscape: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L)
        }
    }
    val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        // Desplazable: con letra al 200 % no cabe y el final (incluido «Apagar (modo prueba)») quedaba cortado.
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(timeText, fontSize = 64.sp, color = Color.White, fontWeight = FontWeight.Light)

                if (sensorText != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🚶", fontSize = 96.sp)
                        Text("Camina hasta el sensor", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "La alarma se apaga cuando llegue una notificación nueva con «$sensorText».",
                            color = Color.LightGray, fontSize = 18.sp, textAlign = TextAlign.Center,
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (showEscape) {
                            OutlinedButton(onClick = onEscape) { Text("Apagar (modo prueba)") }
                        } else {
                            Text(
                                "No se puede silenciar ni apagar hasta que el sensor te detecte.",
                                color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    SquatSection(required, done, aVert, thresholdDown, thresholdUp, sensorAvailable, rejection, showEscape, onEscape)
                }
            }
        }
    }
}

@Composable
private fun SquatSection(
    required: Int,
    done: Int,
    aVert: Float,
    thresholdDown: Float,
    thresholdUp: Float,
    sensorAvailable: Boolean,
    rejection: String,
    showEscape: Boolean,
    onEscape: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Haz $required sentadillas para apagar la alarma",
            color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text("$done", fontSize = 120.sp, color = GREEN, fontWeight = FontWeight.Bold)
        Text("de $required", color = Color.LightGray, fontSize = 22.sp)
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(
            progress = { done.toFloat() / required },
            modifier = Modifier.fillMaxWidth().height(10.dp),
            color = GREEN,
            trackColor = Color.DarkGray,
        )
        Spacer(Modifier.height(16.dp))
        LiveBar(aVert, thresholdDown, thresholdUp)
        if (rejection.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(rejection, color = RED, fontSize = 16.sp, textAlign = TextAlign.Center)
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Sostén el teléfono contra el pecho con ambas manos y haz sentadillas completas, igual que en la calibración.",
            color = Color.LightGray, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        if (!sensorAvailable) {
            Text("⚠ Este teléfono no tiene acelerómetro.", color = RED, textAlign = TextAlign.Center)
        }
        if (showEscape) {
            OutlinedButton(onClick = onEscape) {
                Text(if (sensorAvailable) "Apagar (modo prueba)" else "Apagar")
            }
        } else {
            Text(
                "No se puede silenciar ni apagar hasta completar las sentadillas.",
                color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center,
            )
        }
    }
}
