package com.squatalarm.app.ui

import android.app.TimePickerDialog
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.squatalarm.app.alarm.AlarmPrefs
import com.squatalarm.app.alarm.AlarmScheduler
import com.squatalarm.app.alarm.SensorNotificationListener
import com.squatalarm.app.util.PermissionUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private class HomeState(prefs: AlarmPrefs) {
    var hour by mutableIntStateOf(prefs.hour)
    var minute by mutableIntStateOf(prefs.minute)
    var days by mutableIntStateOf(prefs.repeatDays)
    var squats by mutableIntStateOf(prefs.squatsRequired)
    var enabled by mutableStateOf(prefs.enabled)
    var strictness by mutableFloatStateOf(prefs.strictness)
    var safetyMin by mutableIntStateOf(prefs.safetyTimeoutMin)
    var calibration by mutableStateOf(prefs.calibration)
    var nextAt by mutableLongStateOf(prefs.scheduledAt)
    var missing by mutableIntStateOf(0)
    var mission by mutableIntStateOf(prefs.mission)
    var sensorPackage by mutableStateOf(prefs.sensorPackage)
    var sensorText by mutableStateOf(prefs.sensorText)
    var picking by mutableStateOf(false)

    fun reload(prefs: AlarmPrefs) {
        enabled = prefs.enabled
        calibration = prefs.calibration
        nextAt = prefs.scheduledAt
        strictness = prefs.strictness
    }
}

@Composable
fun HomeScreen(resumeTick: Int, onCalibrate: () -> Unit, onTest: () -> Unit, onPermissions: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AlarmPrefs(context) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val st = remember(reloadKey) { HomeState(prefs) }

    LaunchedEffect(resumeTick, reloadKey) {
        st.reload(prefs)
        st.missing = PermissionUtils.missingCount(context)
    }

    fun save() {
        prefs.hour = st.hour
        prefs.minute = st.minute
        prefs.repeatDays = st.days
        prefs.squatsRequired = st.squats
        prefs.enabled = st.enabled
        prefs.strictness = st.strictness
        prefs.safetyTimeoutMin = st.safetyMin
        prefs.mission = st.mission
        AlarmScheduler.schedule(context)
        st.nextAt = prefs.scheduledAt
        st.missing = PermissionUtils.missingCount(context)
    }

    // Se recalcula al volver de Ajustes (resumeTick) o al cambiar la misión o el sensor.
    val sensorReady = remember(resumeTick, st.mission, st.sensorPackage, st.sensorText) { PermissionUtils.sensorMissionReady(context) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val ok = try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(prefs.exportText().toByteArray()) } != null
            } catch (e: Exception) {
                false
            }
            toast(if (ok) "Parámetros exportados" else "No se pudo exportar")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            toast(
                try {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }.orEmpty()
                    prefs.importText(text)
                    AlarmScheduler.schedule(context)
                    reloadKey++
                    "Parámetros importados"
                } catch (e: Exception) {
                    "Archivo no válido: ${e.message}"
                }
            )
        }
    }

    val timeText = String.format(Locale.US, "%02d:%02d", st.hour, st.minute)
    val nextText = if (st.enabled && st.nextAt > 0L) {
        "Próxima: " + SimpleDateFormat("EEEE d MMM, HH:mm", Locale.forLanguageTag("es-CL")).format(Date(st.nextAt))
    } else {
        "Alarma desactivada"
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding() // el teclado no tapa el campo de texto del sensor
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Alarma Sentadillas", style = MaterialTheme.typography.headlineMedium)
            val version = remember {
                try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { null }
            }
            Text("Versión ${version ?: "?"}", style = MaterialTheme.typography.bodySmall)

            SectionCard("Alarma") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        timeText,
                        style = MaterialTheme.typography.displayLarge,
                        modifier = Modifier.clickable(onClickLabel = "cambiar la hora", role = Role.Button) {
                            TimePickerDialog(
                                context,
                                { _, h, m -> st.hour = h; st.minute = m; save() },
                                st.hour, st.minute, true
                            ).show()
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = st.enabled,
                        onCheckedChange = { st.enabled = it; save() },
                        modifier = Modifier.semantics { contentDescription = "Alarma" },
                    )
                }
                Text("Toca la hora para cambiarla.", style = MaterialTheme.typography.bodySmall)
                DaysRow(st.days) { st.days = it; save() }
                Text(nextText, style = MaterialTheme.typography.bodyMedium)
                Text("Sentadillas para apagarla: ${st.squats}")
                Slider(
                    value = st.squats.toFloat(),
                    onValueChange = { st.squats = it.roundToInt() },
                    onValueChangeFinished = { save() },
                    valueRange = 3f..40f,
                    steps = 36,
                    modifier = Modifier.labeled("Sentadillas para apagarla", "${st.squats}"),
                )
                Text("Apagado de seguridad: ${st.safetyMin} min")
                Slider(
                    value = st.safetyMin.toFloat(),
                    onValueChange = { st.safetyMin = (it / 5f).roundToInt() * 5 },
                    onValueChangeFinished = { save() },
                    valueRange = 5f..180f,
                    steps = 34,
                    modifier = Modifier.labeled("Apagado de seguridad", "${st.safetyMin} minutos"),
                )
                Text(
                    "Si la detección fallara, la alarma se apaga sola tras ese tiempo. Es la única salida sin cumplir la misión.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (st.missing > 0) {
                    Text(
                        "⚠ Faltan ${st.missing} permisos: la alarma podría no sonar o podría apagarse el teléfono.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            SectionCard("Para apagar la alarma") {
                Column(Modifier.selectableGroup()) {
                    MissionOption("Hacer sentadillas", st.mission == AlarmPrefs.MISSION_SQUATS) {
                        st.mission = AlarmPrefs.MISSION_SQUATS; save()
                    }
                    MissionOption("Caminar hasta un sensor de movimiento", st.mission == AlarmPrefs.MISSION_SENSOR) {
                        st.mission = AlarmPrefs.MISSION_SENSOR; save()
                    }
                }
                if (st.mission == AlarmPrefs.MISSION_SENSOR) {
                    Text(
                        "Se apaga cuando llega una notificación nueva del sensor (p. ej. SwitchBot «Movimiento detectado»). " +
                            "La app la lee con «Acceso a notificaciones»; no hace capturas de pantalla.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (st.sensorPackage.isNotEmpty()) {
                        Text("App del sensor: ${st.sensorPackage}", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = st.sensorText,
                            onValueChange = { st.sensorText = it; prefs.sensorText = it },
                            label = { Text("Texto que debe traer la notificación") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (!sensorReady) {
                        Text(
                            "Mientras no elijas la notificación del sensor o falte «Acceso a notificaciones» (Revisar permisos), " +
                                "la alarma se apaga con sentadillas.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    OutlinedButton(onClick = { st.picking = !st.picking }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (st.picking) "Cancelar" else "Elegir la notificación del sensor")
                    }
                    if (st.picking) {
                        SensorPicker { s ->
                            st.sensorPackage = s.pkg
                            st.sensorText = s.title.ifBlank { s.text }
                            prefs.sensorPackage = st.sensorPackage
                            prefs.sensorText = st.sensorText
                            st.picking = false
                        }
                    }
                }
            }

            SectionCard("Detección de sentadillas") {
                val c = st.calibration
                Text(
                    if (c.calibrated) {
                        "✓ Preconfigurada con ${c.nSquats} sentadillas · sensibilidad ${pct(c.sens)} · especificidad ${pct(c.spec)} (medias posteriores)" +
                            (if (c.valSens >= 0f) "\nValidación: sensibilidad ${pct(c.valSens)} · especificidad ${pct(c.valSpec)}" else "\nSin validar") +
                            "\n" + vetoSummary(c)
                    } else {
                        "Sin preconfigurar: se usan valores por defecto. Preconfigura antes de usar la alarma."
                    },
                    color = if (c.calibrated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCalibrate, modifier = Modifier.weight(1f)) { Text("Preconfigurar") }
                    OutlinedButton(onClick = onTest, modifier = Modifier.weight(1f)) { Text("Probar detección") }
                }
                Text("Exigencia del movimiento: ×%.1f".format(st.strictness), style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = st.strictness,
                    onValueChange = { st.strictness = (it * 10f).roundToInt() / 10f },
                    onValueChangeFinished = { save() },
                    valueRange = 0.5f..1.8f,
                    modifier = Modifier.labeled("Exigencia del movimiento", "×%.1f".format(st.strictness)),
                )
            }

            SectionCard("Respaldo de parámetros") {
                Text(
                    "Al instalar una versión nueva encima de la anterior, los parámetros se conservan. " +
                        "Exporta un respaldo por si desinstalas la app o cambias de teléfono.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportLauncher.launch("squatalarm-parametros.txt") }, modifier = Modifier.weight(1f)) {
                        Text("Exportar")
                    }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/*")) }, modifier = Modifier.weight(1f)) {
                        Text("Importar")
                    }
                }
            }

            SectionCard("Permisos") {
                Text(if (st.missing == 0) "✓ Todo concedido" else "Faltan ${st.missing} permisos")
                Button(onClick = onPermissions, modifier = Modifier.fillMaxWidth()) { Text("Revisar permisos") }
            }

            SectionCard("Prueba completa") {
                Text(
                    "Hace sonar la alarma en 10 s con sonido, vibración y bloqueo reales. " +
                        "Bloquea el teléfono y espera. En modo prueba hay un botón para apagarla.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        AlarmScheduler.ringIn(context, 10_000L, test = true)
                        Toast.makeText(context, "La alarma sonará en 10 segundos", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Probar alarma en 10 s") }
            }
        }
    }
}

@Composable
private fun MissionOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null) // la fila entera es el control (un solo foco para TalkBack)
        Text(label)
    }
}

/** Lista las notificaciones visibles y las que van llegando; tocar una la elige como la del sensor. */
@Composable
private fun SensorPicker(onPick: (SensorNotificationListener.Seen) -> Unit) {
    val items = remember { mutableStateListOf<SensorNotificationListener.Seen>().apply { addAll(SensorNotificationListener.current()) } }
    DisposableEffect(Unit) {
        SensorNotificationListener.picker = { s -> items.add(0, s) }
        onDispose { SensorNotificationListener.picker = null }
    }
    if (SensorNotificationListener.instance == null) {
        Text(
            "⚠ Primero concede «Acceso a notificaciones» en Revisar permisos y vuelve aquí.",
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    Text(
        "Toca la notificación del sensor. Si no está en la lista, pasa frente al sensor: la nueva aparecerá aquí.",
        style = MaterialTheme.typography.bodySmall,
    )
    items.distinctBy { Triple(it.pkg, it.title, it.text) }.take(12).forEach { s ->
        Column(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(vertical = 6.dp)) {
            Text(s.title.ifBlank { s.text }, style = MaterialTheme.typography.bodyMedium)
            Text("${s.pkg} · ${s.text}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DaysRow(days: Int, onChange: (Int) -> Unit) {
    val labels = listOf("L", "M", "X", "J", "V", "S", "D")
    val names = listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEachIndexed { i, label ->
            val dow = if (i == 6) Calendar.SUNDAY else i + 2 // L=2 … S=7, D=1
            val bit = 1 shl (dow - 1)
            val selected = (days and bit) != 0
            Surface(
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(40.dp).toggleable(value = selected, role = Role.Checkbox) { onChange(days xor bit) },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        modifier = Modifier.semantics { contentDescription = names[i] },
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
