package com.squatalarm.app.ui

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material3.TextButton
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
import com.squatalarm.app.alarm.AlarmVibration
import com.squatalarm.app.alarm.MorningLog
import com.squatalarm.app.alarm.MorningSummary
import com.squatalarm.app.alarm.RingPlan
import com.squatalarm.app.alarm.SensorMatch
import com.squatalarm.app.alarm.SensorNotificationListener
import com.squatalarm.app.block.DeviceOwnerHelper
import com.squatalarm.app.util.PermissionUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
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
    var softStart by mutableStateOf(prefs.softStart)
    var soundSoft by mutableStateOf(prefs.soundSoft)
    var soundLoud by mutableStateOf(prefs.soundLoud)
    var volumeSoft by mutableFloatStateOf(prefs.volumeSoft)
    var volumeLoud by mutableFloatStateOf(prefs.volumeLoud)
    var sensorLastHit by mutableStateOf(prefs.sensorLastHit)

    fun reload(prefs: AlarmPrefs) {
        enabled = prefs.enabled
        calibration = prefs.calibration
        nextAt = prefs.scheduledAt
        strictness = prefs.strictness
        sensorLastHit = prefs.sensorLastHit
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

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    /** [announce]: al activar la alarma o cambiar la hora o los días, avisa cuánto falta para que suene. */
    fun save(announce: Boolean = false) {
        prefs.hour = st.hour
        prefs.minute = st.minute
        prefs.repeatDays = st.days
        prefs.squatsRequired = st.squats
        prefs.enabled = st.enabled
        prefs.strictness = st.strictness
        prefs.safetyTimeoutMin = st.safetyMin
        prefs.mission = st.mission
        prefs.softStart = st.softStart
        prefs.soundSoft = st.soundSoft
        prefs.soundLoud = st.soundLoud
        prefs.volumeSoft = st.volumeSoft
        prefs.volumeLoud = st.volumeLoud
        AlarmScheduler.schedule(context)
        st.nextAt = prefs.scheduledAt
        st.missing = PermissionUtils.missingCount(context)
        if (announce && st.enabled && st.nextAt > 0L) toast("Sonará en " + RingPlan.untilText(st.nextAt - System.currentTimeMillis()))
    }

    // Se recalcula al volver de Ajustes (resumeTick) o al cambiar la misión o el sensor.
    val sensorReady = remember(resumeTick, st.mission, st.sensorPackage, st.sensorText) { PermissionUtils.sensorMissionReady(context) }

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
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val ok = try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(MorningLog.logFile(context).readBytes()) } != null
            } catch (e: Exception) {
                false
            }
            toast(if (ok) "Registro exportado" else "No se pudo exportar")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            toast(
                try {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }.orEmpty()
                    prefs.importText(text)
                    MorningLog.forgetPosterior(context) // la evidencia guardada era de la calibración anterior
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
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(20_000L)
        }
    }
    val nextText = if (st.enabled && st.nextAt > 0L) {
        "Próxima: " + SimpleDateFormat("EEEE d MMM, HH:mm", Locale.forLanguageTag("es-CL")).format(Date(st.nextAt)) +
            " (en ${RingPlan.untilText(st.nextAt - now)})"
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
                                { _, h, m -> st.hour = h; st.minute = m; save(announce = true) },
                                st.hour, st.minute, true
                            ).show()
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = st.enabled,
                        onCheckedChange = { st.enabled = it; save(announce = it) },
                        modifier = Modifier.semantics { contentDescription = "Alarma" },
                    )
                }
                Text("Toca la hora para cambiarla.", style = MaterialTheme.typography.bodySmall)
                DaysRow(st.days) { st.days = it; save(announce = true) }
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
                    OutlinedTextField(
                        value = st.sensorText,
                        onValueChange = { st.sensorText = it; prefs.sensorText = it },
                        label = { Text("Texto de la notificación") },
                        placeholder = { Text("p. ej. Movimiento detectado") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // La app no se escribe a mano: un paquete mal escrito haría esperar una notificación que nunca llega.
                    if (st.sensorPackage.isEmpty()) {
                        Text(
                            "Sirve una notificación de cualquier app con ese texto (al menos ${SensorMatch.MIN_TEXT_ANY_APP} letras). " +
                                "Para aceptar solo la del sensor, elígela de las notificaciones.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Solo de la app ${st.sensorPackage}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { st.sensorPackage = ""; prefs.sensorPackage = "" }) { Text("Cualquier app") }
                        }
                    }
                    if (!sensorReady) {
                        Text(
                            "Mientras falte el texto de la notificación o «Acceso a notificaciones» (Revisar permisos), " +
                                "la alarma se apaga con sentadillas.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (st.sensorLastHit.isNotEmpty()) {
                        Text("Última notificación que la apagó: ${st.sensorLastHit}", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { st.picking = !st.picking }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (st.picking) "Cancelar" else "O elegirla de las notificaciones")
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

            SectionCard("Sonido") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Inicio suave")
                        Text(
                            "Para no despertar a otros: 90 s de vibración y sonido bajo, 2 min de silencio y después la alarma " +
                                "completa (a los 3½ min). Las sentadillas la apagan en cualquier fase.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = st.softStart,
                        onCheckedChange = { st.softStart = it; save() },
                        modifier = Modifier.semantics { contentDescription = "Inicio suave" },
                    )
                }
                if (st.softStart) {
                    SoundChoice("Sonido suave", RingtoneManager.TYPE_RINGTONE, st.soundSoft, st.volumeSoft, 0.05f..0.6f,
                        onSound = { st.soundSoft = it; save() }, onVolume = { st.volumeSoft = it }, onVolumeDone = { save() })
                }
                SoundChoice("Sonido de la alarma", RingtoneManager.TYPE_ALARM, st.soundLoud, st.volumeLoud, 0.3f..1f,
                    onSound = { st.soundLoud = it; save() }, onVolume = { st.volumeLoud = it }, onVolumeDone = { save() })
                OutlinedButton(
                    // 3 s del patrón de la alarma: si aquí no vibra, es un ajuste del teléfono, no la app.
                    onClick = { AlarmVibration.vibrate(context, AlarmVibration.LOUD + AlarmVibration.LOUD.drop(1) + AlarmVibration.LOUD.drop(1), -1) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Probar vibración") }
                Text(
                    "Si no vibra, revisa en Ajustes → Sonido y vibración la vibración de alarmas (y que no esté desactivada toda la vibración).",
                    style = MaterialTheme.typography.bodySmall,
                )
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

            val rows = remember(resumeTick) { MorningLog.rows(context) }
            val hasPosterior = remember(resumeTick) { MorningLog.hasPosterior(context) }
            SectionCard("Registro de mañanas") {
                Text(
                    when {
                        !st.calibration.calibrated -> "Preconfigura para que la detección aprenda de tus alarmas."
                        !hasPosterior -> "Para que la detección aprenda de tus alarmas, preconfigura una vez más: " +
                            "las preconfiguraciones anteriores no guardaban la evidencia."
                        else -> "Aprendió de ${prefs.learnedAlarms} alarmas. Cada una pesa 0,2 de un paso de la preconfiguración " +
                            "y la detección solo cambia si la mejora es muy clara (≈97,5 % de probabilidad)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (rows.isEmpty()) {
                    Text("Aún no hay registros: al apagar cada alarma real te preguntará cómo amaneciste.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        "${rows.size} mañanas. Por factor (sí · no): mañanas, ánimo de −1 ☹ a +1 🙂 y minutos hasta apagarla.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    MorningSummary.factors(rows).forEach { f ->
                        Text("${f.name}: sí ${groupText(f.yes)} · no ${groupText(f.no)}")
                    }
                    if (rows.size < 10) {
                        Text("Con menos de 10 mañanas, las diferencias son sobre todo azar.", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { csvLauncher.launch("squatalarm-registro.csv") }, modifier = Modifier.fillMaxWidth()) {
                        Text("Exportar registro (CSV)")
                    }
                }
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
                    "Hace sonar la alarma en 10 s con sonido, vibración y bloqueo reales (con inicio suave, si está activado). " +
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

            var owner by remember(resumeTick) { mutableStateOf(DeviceOwnerHelper.isOwner(context)) }
            if (owner) {
                SectionCard("Bloqueo al 100 % (device owner)") {
                    Text(
                        "Activo: mientras suena no hay menú de apagado ni forma de llamar a emergencias, y la app no se puede desinstalar.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = {
                            owner = !DeviceOwnerHelper.clear(context)
                            toast(if (owner) "No se pudo quitar" else "Bloqueo al 100 % quitado")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Quitar bloqueo al 100 %") }
                }
            }
        }
    }
}

private fun groupText(g: MorningSummary.Group): String =
    if (g.n == 0) "—"
    else "${g.n} (ánimo ${g.mood?.let { "%+.1f".format(it) } ?: "—"}, ${g.minutes?.let { "%.1f".format(it) } ?: "—"} min)"

/** Sonido de una fase (selector de tonos del sistema, de [type]) y su volumen, como fracción del máximo de alarma. */
@Composable
private fun SoundChoice(
    label: String,
    type: Int,
    uri: String,
    volume: Float,
    range: ClosedFloatingPointRange<Float>,
    onSound: (String) -> Unit,
    onVolume: (Float) -> Unit,
    onVolumeDone: () -> Unit,
) {
    val context = LocalContext.current
    val current = if (uri.isEmpty()) Settings.System.DEFAULT_ALARM_ALERT_URI else Uri.parse(uri)
    val title = remember(uri) {
        // stop(): getRingtone prepara un reproductor que si no queda abierto
        if (uri.isEmpty()) null else try { RingtoneManager.getRingtone(context, current)?.let { r -> r.getTitle(context).also { r.stop() } } } catch (e: Exception) { null }
    } ?: "Alarma predeterminada"
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        @Suppress("DEPRECATION")
        val picked = r.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (r.resultCode == Activity.RESULT_OK && picked != null) onSound(if (picked == Settings.System.DEFAULT_ALARM_ALERT_URI) "" else picked.toString())
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label: $title", modifier = Modifier.weight(1f))
        OutlinedButton(modifier = Modifier.semantics { contentDescription = "Elegir $label" }, onClick = {
            try {
                picker.launch(
                    Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type) // TYPE_ALL rompe «Agregar sonido» del selector
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, label)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, type == RingtoneManager.TYPE_ALARM)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
                )
            } catch (e: Exception) {
                Toast.makeText(context, "Este teléfono no tiene selector de sonidos", Toast.LENGTH_LONG).show()
            }
        }) { Text("Elegir") }
    }
    Text("Volumen: ${pct(volume)}", style = MaterialTheme.typography.bodySmall)
    Slider(
        value = volume,
        onValueChange = { onVolume((it * 20f).roundToInt() / 20f) },
        onValueChangeFinished = onVolumeDone,
        valueRange = range,
        modifier = Modifier.labeled("Volumen de $label", pct(volume)),
    )
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
