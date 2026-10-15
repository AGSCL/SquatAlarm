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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.squatalarm.app.R
import com.squatalarm.app.util.PermissionUtils

/**
 * Servicio en primer plano que hace sonar la alarma a volumen máximo, vibra, mantiene la
 * pantalla encendida y vuelve a traer [AlarmActivity] al frente si deja de verse.
 */
class AlarmService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var startedAt = 0L
    private var safetyTimeoutMs = AlarmPrefs.DEFAULT_SAFETY_TIMEOUT_MIN * 60_000L
    private var ringing = false

    private val watchdog = object : Runnable {
        override fun run() {
            if (!ringing) return
            if (System.currentTimeMillis() - startedAt > safetyTimeoutMs) {
                AlarmPrefs(this@AlarmService).isRinging = false
                stopSelf()
                return
            }
            ensureVolume()
            ensurePlaying()
            // No tapar una llamada (p. ej. de emergencia) ni el marcador abierto para hacerla.
            val inCall = getSystemService(AudioManager::class.java).mode != AudioManager.MODE_NORMAL
            if (!activityVisible && !inCall && SystemClock.elapsedRealtime() > dialerPauseUntil) {
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
        acquireWakeLock()
        ensureVolume()
        startPlayer()
        startVibration()
        bringToFront(this, repostNotification = false)
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun stopRinging() {
        ringing = false
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        val am = getSystemService(AudioManager::class.java)
        focusRequest?.let { try { am.abandonAudioFocusRequest(it) } catch (e: Exception) { } }
        focusRequest = null
        try { vibrator()?.cancel() } catch (e: Exception) { }
        val prefs = AlarmPrefs(this)
        val before = prefs.volumeBefore
        if (before >= 0) {
            try { am.setStreamVolume(AudioManager.STREAM_ALARM, before, 0) } catch (e: Exception) { }
            prefs.volumeBefore = -1
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID_FS)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun ensureVolume() {
        try {
            val am = getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val cur = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val prefs = AlarmPrefs(this)
            if (prefs.volumeBefore < 0) prefs.volumeBefore = cur
            if (cur < max) am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        } catch (e: Exception) { }
    }

    private fun startPlayer() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .build()
            getSystemService(AudioManager::class.java).requestAudioFocus(req)
            focusRequest = req
        } catch (e: Exception) { }
        val candidates = listOfNotNull(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for (uri in candidates) {
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(attrs)
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.setVolume(1f, 1f)
                mp.prepare()
                mp.start()
                player = mp
                return
            } catch (e: Exception) { }
        }
    }

    private fun ensurePlaying() {
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

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private fun startVibration() {
        val v = vibrator() ?: return
        try {
            val effect = VibrationEffect.createWaveform(longArrayOf(0L, 700L, 500L), 0)
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                v.vibrate(effect)
            }
        } catch (e: Exception) { }
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
        /** Hasta cuándo (elapsedRealtime) no traer la alarma al frente: el marcador está abierto (ver PowerMenuBlockerService). */
        @Volatile var dialerPauseUntil = 0L

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
