package com.squatalarm.app.alarm

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.squatalarm.app.R
import com.squatalarm.app.util.PermissionUtils

/**
 * Servicio en primer plano que hace sonar la alarma (con inicio suave opcional, ver [RingPlan]), vibra,
 * mantiene la pantalla encendida y vuelve a traer [AlarmActivity] al frente si deja de verse.
 */
class AlarmService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    // En el silencio no hay audio ni vibración que mantengan la CPU: sin esto, apagar la pantalla con el botón
    // puede dormir el teléfono y atrasar minutos la alarma fuerte.
    private var cpuLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var startedAt = 0L
    private var safetyTimeoutMs = AlarmPrefs.DEFAULT_SAFETY_TIMEOUT_MIN * 60_000L
    private var ringing = false
    private var phase: RingPlan.Phase? = null
    private var soundUri = ""
    /** Patrón de vibración de la fase actual (null = sin vibrar); el vigilante lo relanza cada 4 s. */
    private var vibration: LongArray? = null
    // Ajustes de sonido leídos al empezar a sonar: cambiarlos a media alarma no la altera (ni su cuenta regresiva).
    private var softStart = true
    private var soundSoft = ""
    private var soundLoud = ""
    private var volumeSoft = 0.3f
    private var volumeLoud = 1f
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val watchdog = object : Runnable {
        override fun run() {
            if (!ringing) return
            if (System.currentTimeMillis() - startedAt > safetyTimeoutMs) {
                AlarmPrefs(this@AlarmService).isRinging = false
                stopSelf()
                return
            }
            applyPhase()
            ensureVolume()
            ensurePlaying()
            // Android corta una vibración larga (botón de encendido, otra vibración, toques): se relanza siempre.
            vibration?.let { AlarmVibration.vibrate(this@AlarmService, it, 0) }
            if (!activityVisible && !phoneInUse(this@AlarmService)) {
                wakeScreen()
                bringToFront(this@AlarmService, repostNotification = true)
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val prefs = AlarmPrefs(this)
        // ringingActive y no isRinging: si START_STICKY reinicia el servicio de una alarma ya vencida, no vuelve a sonar.
        if (intent?.action == ACTION_STOP || !prefs.ringingActive()) {
            prefs.isRinging = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ringing) startRinging(prefs)
        return START_STICKY
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val n = buildNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            // Sin permiso de primer plano en este momento: seguimos sonando igual.
        }
    }

    private fun startRinging(prefs: AlarmPrefs) {
        ringing = true
        isRunning = true
        startedAt = prefs.ringingSince.takeIf { it > 0L } ?: System.currentTimeMillis()
        safetyTimeoutMs = prefs.safetyTimeoutMin * 60_000L
        softStart = prefs.softStart
        soundSoft = prefs.soundSoft
        soundLoud = prefs.soundLoud
        volumeSoft = prefs.volumeSoft
        volumeLoud = prefs.volumeLoud
        cpuLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SquatAlarm:cpu").apply { acquire(WAKELOCK_TIMEOUT_MS) }
        acquireWakeLock()
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build()
            getSystemService(AudioManager::class.java).requestAudioFocus(req)
            focusRequest = req
        } catch (e: Exception) { }
        applyPhase()
        // Si suena en plena llamada no tapa la pantalla de llamada; el vigilante la trae al colgar o al cambiar de app.
        if (inCall(this)) dialerPauseUntil = SystemClock.elapsedRealtime() + DIALER_PAUSE_MS
        else bringToFront(this, repostNotification = false)
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun stopRinging() {
        ringing = false
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        stopSound()
        phase = null
        focusRequest?.let { try { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) } catch (e: Exception) { } }
        focusRequest = null
        restoreVolume(this)
        dialerPauseUntil = 0L
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        cpuLock?.let { if (it.isHeld) it.release() }
        cpuLock = null
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID_FS)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /** Pasa a la fase que corresponde según el tiempo sonando: cambia sonido y vibración solo al cambiar de fase. */
    private fun applyPhase() {
        val elapsed = System.currentTimeMillis() - startedAt
        val p = RingPlan.phaseAt(elapsed, softStart)
        if (p == phase) return
        phase = p
        stopSound()
        ensureVolume() // antes del sonido: el suave no arranca ni un instante al volumen anterior
        when (p) {
            RingPlan.Phase.SOFT -> { soundUri = soundSoft; startPlayer(); startVibration(AlarmVibration.SOFT) }
            RingPlan.Phase.SILENT -> {}
            RingPlan.Phase.LOUD -> { soundUri = soundLoud; startPlayer(); startVibration(AlarmVibration.LOUD) }
        }
        // El cambio de fase llega a su hora exacta, no en el siguiente chequeo del vigilante (hasta 4 s después).
        val next = when (p) {
            RingPlan.Phase.SOFT -> RingPlan.SOFT_MS
            RingPlan.Phase.SILENT -> RingPlan.SOFT_MS + RingPlan.SILENT_MS
            RingPlan.Phase.LOUD -> return
        }
        handler.postDelayed({ if (ringing) applyPhase() }, (next - elapsed).coerceAtLeast(0L))
    }

    private fun stopSound() {
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        vibration = null
        AlarmVibration.cancel(this)
    }

    /** Volumen de la fase (elegido por el usuario); se reimpone cada 4 s: los botones de volumen no lo cambian. */
    private fun ensureVolume() {
        val fraction = when (phase) {
            RingPlan.Phase.SOFT -> volumeSoft
            RingPlan.Phase.LOUD -> volumeLoud
            else -> return
        }
        val prefs = AlarmPrefs(this)
        try {
            val am = getSystemService(AudioManager::class.java)
            val cur = am.getStreamVolume(AudioManager.STREAM_ALARM)
            if (prefs.volumeBefore < 0) prefs.volumeBefore = cur
            val target = RingPlan.volumeIndex(fraction, am.getStreamMaxVolume(AudioManager.STREAM_ALARM))
            if (cur != target) am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        } catch (e: Exception) { }
    }

    private fun startPlayer() {
        val candidates = listOfNotNull(
            // Antes del primer desbloqueo, un sonido elegido puede no estar disponible: siguen los de respaldo.
            soundUri.takeIf { it.isNotEmpty() }?.let(Uri::parse),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            Uri.parse("android.resource://$packageName/${R.raw.alarm_fallback}"), // incluido en la app: siempre disponible
        )
        for (uri in candidates) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs)
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.setVolume(1f, 1f)
                mp.prepare()
                mp.start()
                player = mp
                return
            } catch (e: Exception) {
                mp.release() // si no, cada intento fallido deja un reproductor nativo abierto
            }
        }
    }

    private fun ensurePlaying() {
        if (phase == null || phase == RingPlan.Phase.SILENT) return
        val p = player
        if (p == null) {
            startPlayer()
            return
        }
        try {
            if (!p.isPlaying) p.start()
        } catch (e: Exception) {
            player = null
            startPlayer()
        }
    }

    private fun startVibration(pattern: LongArray) {
        vibration = pattern
        AlarmVibration.vibrate(this, pattern, 0)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "SquatAlarm:ring"
        )
        wl.acquire(WAKELOCK_TIMEOUT_MS)
        wakeLock = wl
    }

    /** Soltar y volver a tomar el wakelock enciende la pantalla si el usuario la apagó con el botón. */
    private fun wakeScreen() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            acquireWakeLock()
        } catch (e: Exception) { }
    }

    companion object {
        const val CHANNEL_ID = "alarm_channel"
        private const val NOTIF_ID = 42
        private const val NOTIF_ID_FS = 43
        private const val ACTION_STOP = "com.squatalarm.app.action.STOP_SERVICE"
        private const val WATCHDOG_MS = 4_000L
        private const val WAKELOCK_TIMEOUT_MS = 3L * 60 * 60 * 1000

        @Volatile var isRunning = false
        @Volatile var activityVisible = false
        /**
         * Teléfono en uso (marcador o llamada, también de emergencia): hasta cuándo (elapsedRealtime) no traer la alarma
         * al frente; 0 = no. Lo marca PowerMenuBlockerService y se borra al abrir otra app o al volver a la alarma.
         */
        @Volatile var dialerPauseUntil = 0L
        const val DIALER_PAUSE_MS = 120_000L

        private fun inCall(context: Context) =
            context.getSystemService(AudioManager::class.java).mode != AudioManager.MODE_NORMAL

        /** Con el teléfono en uso, una llamada en curso no se tapa nunca; el marcador, por [DIALER_PAUSE_MS]. */
        private fun phoneInUse(context: Context): Boolean {
            val until = dialerPauseUntil
            return until != 0L && (inCall(context) || SystemClock.elapsedRealtime() < until)
        }

        /** Vuelve al volumen de alarma previo; también desde BootReceiver cuando la alarma no se reanuda. */
        fun restoreVolume(context: Context) {
            val prefs = AlarmPrefs(context)
            val before = prefs.volumeBefore
            if (before < 0) return
            try { context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, before, 0) } catch (e: Exception) { }
            prefs.volumeBefore = -1
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java))
        }

        fun stop(context: Context) {
            AlarmPrefs(context).isRinging = false
            context.stopService(Intent(context, AlarmService::class.java))
        }

        /** Vuelve a mostrar la pantalla de alarma (intent de pantalla completa + startActivity). */
        fun bringToFront(context: Context, repostNotification: Boolean) {
            if (repostNotification) {
                try {
                    val nm = context.getSystemService(NotificationManager::class.java)
                    nm.cancel(NOTIF_ID_FS)
                    nm.notify(NOTIF_ID_FS, buildNotification(context))
                } catch (e: Exception) { }
            }
            try {
                context.startActivity(alarmIntent(context))
            } catch (e: Exception) { }
        }

        private fun alarmIntent(context: Context): Intent =
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private fun buildNotification(context: Context): Notification {
            val prefs = AlarmPrefs(context)
            val task = if (PermissionUtils.sensorMissionReady(context)) "Camina hasta el sensor para apagarla"
            else "Haz ${prefs.squatsRequired} sentadillas para apagarla"
            val pi = PendingIntent.getActivity(
                context, 7, alarmIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle("¡Alarma sonando!")
                .setContentText(task)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build()
        }
    }
}
