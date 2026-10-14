package com.squatalarm.app.ui

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.squatalarm.app.squat.Calibration
import com.squatalarm.app.squat.SquatCounter

val GREEN = Color(0xFF66BB6A)
val RED = Color(0xFFEF5350)

@Composable
fun SquatAlarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TextButton(onClick = onBack) { Text("← Volver") }
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun BigNumber(text: String, color: Color = Color.Unspecified) {
    Text(
        text = text,
        fontSize = 96.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = color,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** GIF de postura de assets/guides (animado en Android 9+, primer cuadro en 8.x). */
@Composable
fun GuideGif(name: String, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> ImageView(ctx).apply { adjustViewBounds = true } },
        update = { view ->
            if (view.tag != name) {
                view.tag = name
                val path = "guides/$name.gif"
                try {
                    if (Build.VERSION.SDK_INT >= 28) {
                        val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(view.context.assets, path))
                        view.setImageDrawable(d)
                        (d as? AnimatedImageDrawable)?.start()
                    } else {
                        view.context.assets.open(path).use { view.setImageBitmap(BitmapFactory.decodeStream(it)) }
                    }
                } catch (e: Exception) {
                    view.setImageDrawable(null)
                }
            }
        },
        modifier = modifier.fillMaxWidth().height(220.dp),
    )
}

/** Parámetros de detección en una línea. */
fun vetoSummary(c: Calibration): String {
    val peak = if (c.maxPeak >= SquatCounter.NO_LIMIT) "sin límite" else "%.1f m/s²".format(c.maxPeak)
    return "Bajada %.1f · subida %.1f m/s² · mín. %d ms · pico máx. %s · inclinación %.0f–%.0f°".format(
        c.thresholdDown, c.thresholdUp, c.minCycleMs, peak, c.tiltMinDeg, c.tiltMaxDeg,
    )
}

/** Explicación breve de por qué un ciclo no se contó. */
fun rejectText(r: SquatCounter.Reject): String = when (r) {
    SquatCounter.Reject.TOO_HARD -> "No contó: movimiento demasiado brusco (¿con los brazos?)"
    SquatCounter.Reject.TOO_LITTLE_TILT -> "No contó: el torso no se inclinó (¿solo brazos?)"
    SquatCounter.Reject.TOO_MUCH_TILT -> "No contó: demasiada inclinación (¿agachado sin doblar las rodillas?)"
    SquatCounter.Reject.NONE -> ""
}

/** Aceleración vertical en vivo con los umbrales de bajada (rojo) y subida (verde). */
@Composable
fun LiveBar(aVert: Float, thresholdDown: Float, thresholdUp: Float, modifier: Modifier = Modifier) {
    val range = 5f
    val neutral = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = modifier.fillMaxWidth().height(28.dp)) {
        val w = size.width
        val h = size.height
        fun x(v: Float): Float = ((v.coerceIn(-range, range) + range) / (2f * range)) * w
        drawRoundRect(color = Color.Gray.copy(alpha = 0.25f), cornerRadius = CornerRadius(h / 2f, h / 2f))
        drawLine(color = neutral.copy(alpha = 0.6f), start = Offset(x(0f), 0f), end = Offset(x(0f), h), strokeWidth = 2f)
        drawLine(color = RED, start = Offset(x(-thresholdDown), 0f), end = Offset(x(-thresholdDown), h), strokeWidth = 3f)
        drawLine(color = GREEN, start = Offset(x(thresholdUp), 0f), end = Offset(x(thresholdUp), h), strokeWidth = 3f)
        val dot = when {
            aVert > thresholdUp -> GREEN
            aVert < -thresholdDown -> RED
            else -> neutral
        }
        drawCircle(color = dot, radius = h * 0.38f, center = Offset(x(aVert), h / 2f))
    }
}
