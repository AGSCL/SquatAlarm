package com.squatalarm.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Tras reiniciar: si la alarma estaba sonando vuelve a sonar; y reprograma la siguiente. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = AlarmPrefs(context)
        if (prefs.isRinging) {
            val ringingFor = System.currentTimeMillis() - prefs.ringingSince
            if (ringingFor in 0L..(prefs.safetyTimeoutMin * 60_000L)) {
                AlarmScheduler.ringIn(context, 2_000L, prefs.isTestRing)
            } else {
                prefs.isRinging = false
            }
        }
        AlarmScheduler.schedule(context)
    }
}
