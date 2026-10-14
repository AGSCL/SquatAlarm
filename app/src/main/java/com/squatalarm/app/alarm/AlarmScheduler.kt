package com.squatalarm.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.squatalarm.app.MainActivity
import java.util.Calendar

object AlarmScheduler {
    private const val RC_FIRE = 1001
    private const val RC_RING = 1002

    /** Próximo disparo según hora y días, o null si está desactivada. */
    fun nextTriggerMillis(prefs: AlarmPrefs, now: Long = System.currentTimeMillis()): Long? {
        if (!prefs.enabled) return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, prefs.hour)
            set(Calendar.MINUTE, prefs.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (prefs.repeatDays == 0) {
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }
        repeat(8) {
            val bit = 1 shl (cal.get(Calendar.DAY_OF_WEEK) - 1)
            if ((prefs.repeatDays and bit) != 0 && cal.timeInMillis > now) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    /** (Re)programa la alarma principal a partir de las preferencias. */
    fun schedule(context: Context) {
        val prefs = AlarmPrefs(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = broadcast(context, RC_FIRE, AlarmReceiver.ACTION_FIRE)
        am.cancel(pi)
        val at = nextTriggerMillis(prefs)
        if (at == null) {
            prefs.scheduledAt = 0L
            return
        }
        setExact(context, am, at, pi)
        prefs.scheduledAt = at
    }

    /** Hace sonar la alarma en [delayMs] sin tocar la programación (prueba o reanudación tras reinicio). */
    fun ringIn(context: Context, delayMs: Long, test: Boolean) {
        AlarmPrefs(context).isTestRing = test
        val am = context.getSystemService(AlarmManager::class.java)
        setExact(context, am, System.currentTimeMillis() + delayMs, broadcast(context, RC_RING, AlarmReceiver.ACTION_RING))
    }

    private fun setExact(context: Context, am: AlarmManager, at: Long, pi: PendingIntent) {
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } catch (e: SecurityException) {
            // Sin permiso de alarmas exactas (Android 12): mejor inexacta que nada.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun broadcast(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode, Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
