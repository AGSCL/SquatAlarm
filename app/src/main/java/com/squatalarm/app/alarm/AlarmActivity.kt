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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.squatalarm.app.squat.SquatCounter
import com.squatalarm.app.squat.SquatDetector
import com.squatalarm.app.ui.AlarmScreen
import com.squatalarm.app.ui.rejectText

/**
 * Pantalla de alarma: se muestra sobre el bloqueo, fija la pantalla (bloquea Inicio/Recientes),
 * ignora Atrás y los botones de volumen, y solo se cierra al completar las sentadillas.
 */
class AlarmActivity : ComponentActivity(), SquatDetector.Listener {

    private lateinit var prefs: AlarmPrefs
    private var detector: SquatDetector? = null
    private var required = 10
    private var finished = false
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
        val det = SquatDetector.fromPrefs(this, prefs, this)
        detector = det
        onBackPressedDispatcher.addCallback(this) { /* Atrás bloqueado */ }
        if (!AlarmService.isRunning) AlarmService.start(this)
        val showEscape = prefs.isTestRing || !det.isAvailable
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AlarmScreen(
                    required = required,
                    done = done.intValue,
                    aVert = aVert.floatValue,
                    thresholdDown = det.counter.thresholdDown,
                    thresholdUp = det.counter.thresholdUp,
                    sensorAvailable = det.isAvailable,
                    rejection = rejection.value,
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
        detector?.start()
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
