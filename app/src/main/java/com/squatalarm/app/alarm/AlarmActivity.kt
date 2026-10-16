package com.squatalarm.app.alarm

import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.squatalarm.app.squat.Segment
import com.squatalarm.app.squat.SquatCounter
import com.squatalarm.app.squat.SquatDetector
import com.squatalarm.app.squat.Tuner
import com.squatalarm.app.ui.AlarmScreen
import com.squatalarm.app.ui.Answers
import com.squatalarm.app.ui.CheckInScreen
import com.squatalarm.app.ui.rejectText
import com.squatalarm.app.ui.vetoSummary
import com.squatalarm.app.util.PermissionUtils
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Pantalla de alarma: se muestra sobre el bloqueo, fija la pantalla (bloquea Inicio/Recientes),
 * ignora Atrás y los botones de volumen, y solo se cierra al completar la misión (sentadillas o sensor).
 */
class AlarmActivity : ComponentActivity(), SquatDetector.Listener {

    private lateinit var prefs: AlarmPrefs
    private var detector: SquatDetector? = null
    private var required = 10
    private var finished = false
    /** Texto de la notificación esperada si la misión es ir hasta el sensor; null = sentadillas. */
    private var sensorText: String? = null
    private val done = mutableIntStateOf(0)
    private val aVert = mutableFloatStateOf(0f)
    private val rejection = mutableStateOf("")
    private var testRing = false
    private var ringStart = 0L
    /** Sensores de los últimos minutos, para aprender de esta alarma. */
    private val recording = Segment("Alarma", true)
    private var lastSampleMs = 0L
    /** Inicio (tiempo del sensor) de la primera sentadilla contada: el aprendizaje cubre desde ahí. */
    private var firstSquatMs = 0L

    /** Registro de la mañana tras apagarla ([askSquats]: la apagaron las sentadillas). */
    private class Ended(val askSquats: Boolean, val counted: Int, val at: Long)
    private val ended = mutableStateOf<Ended?>(null)
    private val saving = mutableStateOf(false)
    private val result = mutableStateOf<String?>(null)
    private var logged = false
    /** Último toque en el registro: el cierre automático cuenta 10 min desde aquí, no desde que se apagó. */
    private val lastTouch = mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AlarmPrefs(this)
        if (!prefs.isRinging) {
            finished = true
            finishAndRemoveTask()
            return
        }
        setupWindow()
        required = prefs.squatsRequired
        sensorText = if (PermissionUtils.sensorMissionReady(this)) prefs.sensorText else null
        testRing = prefs.isTestRing
        ringStart = prefs.ringingSince
        val det = SquatDetector.fromPrefs(this, prefs, this)
        det.recorder = { a, t, x, y, z ->
            recording.add(a, t, x, y, z)
            lastSampleMs = t
            // Recorta de vez en cuando (no en cada muestra): conserva desde la primera sentadilla o los últimos 3 min.
            val from = Tuner.alarmKeepFrom(t, firstSquatMs)
            if (from - recording.startMs > Tuner.ALARM_WINDOW_MS) recording.dropBefore(from)
        }
        detector = det
        // Atrás bloqueado mientras suena; en el registro equivale a «Omitir».
        onBackPressedDispatcher.addCallback(this) { if (ended.value != null && !saving.value) skip() }
        if (!AlarmService.isRunning) AlarmService.start(this)
        val showEscape = prefs.isTestRing || (sensorText == null && !det.isAvailable)
        val loudAt = if (prefs.softStart) prefs.ringingSince + RingPlan.SOFT_MS + RingPlan.SILENT_MS else 0L
        setContent {
            // Se cierra sola si la alarma se apagó por otra vía (sensor o apagado de seguridad).
            LaunchedEffect(Unit) {
                while (!finished) {
                    delay(1000L)
                    if (!prefs.isRinging && !finished) dismiss(bySquats = false)
                }
            }
            val e = ended.value
            // Un registro olvidado no queda abierto para siempre: 10 min sin tocarlo y se cierra (guardando lo automático).
            if (e != null) LaunchedEffect(e, lastTouch.value) { delay(10 * 60_000L); if (!saving.value) skip() }
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (e != null) {
                    CheckInScreen(
                        askSquats = e.askSquats,
                        counted = e.counted,
                        defaultBedMin = prefs.lastBedtimeMin,
                        saving = saving.value,
                        result = result.value,
                        onSave = { save(it) },
                        onSkip = { skip() },
                        onClose = { finishAndRemoveTask() },
                        onInteract = { lastTouch.value = System.currentTimeMillis() },
                    )
                    return@MaterialTheme
                }
                AlarmScreen(
                    required = required,
                    done = done.intValue,
                    aVert = aVert.floatValue,
                    thresholdDown = det.counter.thresholdDown,
                    thresholdUp = det.counter.thresholdUp,
                    sensorAvailable = det.isAvailable,
                    rejection = rejection.value,
                    sensorText = sensorText,
                    showEscape = showEscape,
                    loudAt = loudAt,
                    onEscape = { dismiss(bySquats = false) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (finished) return
        AlarmService.activityVisible = true
        AlarmService.dialerPauseUntil = 0L // volvió a la alarma: termina la pausa del marcador
        if (sensorText == null) detector?.start()
        startLockTaskSafe()
    }

    override fun onStop() {
        super.onStop()
        AlarmService.activityVisible = false
        detector?.stop()
    }

    override fun onSquat(count: Int, squat: SquatCounter.Squat) {
        done.intValue = count
        if (count == 1) firstSquatMs = lastSampleMs - squat.cycleMs
        rejection.value = ""
        if (count >= required && !finished) dismiss(bySquats = true)
    }

    override fun onRejected(squat: SquatCounter.Squat) {
        rejection.value = rejectText(squat.reject)
    }

    override fun onSample(aVert: Float, state: SquatCounter.State) {
        this.aVert.floatValue = aVert
    }

    /**
     * Apaga la alarma. Si era real (no la prueba ni el apagado de seguridad), muestra el registro de la mañana;
     * [bySquats]: la apagaron las sentadillas, así que pregunta cuántas fueron de verdad y aprende.
     */
    private fun dismiss(bySquats: Boolean) {
        if (finished) return
        finished = true
        detector?.stop()
        stopLockTaskSafe()
        AlarmService.stop(this)
        val timedOut = System.currentTimeMillis() - ringStart >= prefs.safetyTimeoutMin * 60_000L
        if (testRing || timedOut) {
            finishAndRemoveTask()
            return
        }
        // Ya no suena: la pantalla vuelve a apagarse sola (sobre todo con el sensor, que te dejó lejos del teléfono).
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ended.value = Ended(bySquats, done.intValue, System.currentTimeMillis())
    }

    private fun save(answers: Answers) {
        val e = ended.value ?: return
        if (logged) return
        logged = true
        saving.value = true
        // Aprender re-analiza ~3 min de sensores con todas las θ (unos segundos): fuera del hilo principal.
        Thread {
            val msg = try {
                val learned = if (e.askSquats && answers.real != null) MorningLog.learn(this, recording, answers.real, e.counted, firstSquatMs) else ""
                writeRow(e, answers, learned)
                answers.bedMin?.let { prefs.lastBedtimeMin = it }
                buildString {
                    append("Registro guardado.")
                    if (learned.isNotEmpty()) append("\nDetección: ").append(learned)
                    if (learned.startsWith("cambió")) append("\n").append(vetoSummary(prefs.calibration))
                }
            } catch (x: Exception) {
                "No se pudo guardar el registro (${x.message})." // sin esto, un error de disco cerraría la app
            }
            runOnUiThread {
                result.value = msg
                saving.value = false
            }
        }.start()
    }

    /** Omitir: guarda solo lo automático (hora, minutos hasta apagarla, conteo) y cierra. */
    private fun skip() {
        logAutomatic()
        finishAndRemoveTask()
    }

    /** La fila de la mañana con solo lo automático, si aún no se guardó nada. */
    private fun logAutomatic() {
        val e = ended.value ?: return
        if (logged) return
        logged = true
        try { writeRow(e, Answers(null, null, null, null, null, null), "") } catch (x: Exception) { }
    }

    /** Si el sistema cierra el registro (otra alarma, cambio de configuración), la mañana igual queda anotada. */
    override fun onDestroy() {
        logAutomatic()
        super.onDestroy()
    }

    private fun writeRow(e: Ended, a: Answers, learned: String) {
        val cal = Calendar.getInstance().apply { timeInMillis = ringStart }
        val ringMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        MorningLog.append(
            this,
            CheckIn(
                date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ringStart)),
                time = CheckIn.hhmm(ringMin),
                ringAt = ringStart,
                minutesToDismiss = (e.at - ringStart) / 60_000.0,
                mission = if (sensorText != null) "sensor" else "sentadillas",
                counted = if (e.askSquats) e.counted else null,
                real = a.real,
                mood = a.mood,
                alcohol = a.alcohol,
                smoked = a.smoked,
                bedtime = a.bedMin?.let(CheckIn::hhmm),
                hoursInBed = a.bedMin?.let { CheckIn.hoursInBed(it, ringMin) },
                stressed = a.stressed,
                learned = learned,
            ),
        )
    }

    /** Si suena otra alarma con el registro anterior aún abierto, la pantalla se rehace para la alarma nueva. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (finished && prefs.ringingActive()) recreate()
    }

    /** Volumen, cámara y botones multimedia no hacen nada mientras suena. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = when {
        finished -> super.dispatchKeyEvent(event)
        event.keyCode in BLOCKED_KEYS -> true
        else -> super.dispatchKeyEvent(event)
    }

    private companion object {
        val BLOCKED_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        )
    }

    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startLockTaskSafe() {
        try {
            val am = getSystemService(ActivityManager::class.java)
            if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) startLockTask()
        } catch (e: Exception) { }
    }

    private fun stopLockTaskSafe() {
        try {
            val am = getSystemService(ActivityManager::class.java)
            if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) stopLockTask()
        } catch (e: Exception) { }
    }
}
