package com.squatalarm.app.alarm

import android.app.ActivityManager
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
import com.squatalarm.app.squat.SquatCounter
import com.squatalarm.app.squat.SquatDetector
import com.squatalarm.app.ui.AlarmScreen
import com.squatalarm.app.ui.rejectText
import com.squatalarm.app.util.PermissionUtils
import kotlinx.coroutines.delay

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
        val det = SquatDetector.fromPrefs(this, prefs, this)
        detector = det
        onBackPressedDispatcher.addCallback(this) { /* Atrás bloqueado */ }
        if (!AlarmService.isRunning) AlarmService.start(this)
        val showEscape = prefs.isTestRing || (sensorText == null && !det.isAvailable)
        setContent {
            // Se cierra sola si la alarma se apagó por otra vía (sensor o apagado de seguridad).
            LaunchedEffect(Unit) {
                while (!finished) {
                    delay(1000L)
                    if (!prefs.isRinging && !finished) dismiss()
                }
            }
            MaterialTheme(colorScheme = darkColorScheme()) {
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
                    onEscape = { dismiss() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (finished) return
        AlarmService.activityVisible = true
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
        rejection.value = ""
        if (count >= required && !finished) dismiss()
    }

    override fun onRejected(squat: SquatCounter.Squat) {
        rejection.value = rejectText(squat.reject)
    }

    override fun onSample(aVert: Float, state: SquatCounter.State) {
        this.aVert.floatValue = aVert
    }

    private fun dismiss() {
        finished = true
        detector?.stop()
        stopLockTaskSafe()
        AlarmService.stop(this)
        finishAndRemoveTask()
    }

    /** Volumen, cámara y botones multimedia no hacen nada mientras suena. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> true
        else -> super.dispatchKeyEvent(event)
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
