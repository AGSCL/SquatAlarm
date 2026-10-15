package com.squatalarm.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Tras reiniciar: si la alarma estaba sonando vuelve a sonar; y reprograma la siguiente. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = AlarmPrefs(context)
        if (prefs.ringingActive()) {
            AlarmScheduler.ringIn(context, 2_000L, prefs.isTestRing)
        } else {
            prefs.isRinging = false
            AlarmService.restoreVolume(context) // sonaba al reiniciarse y ya venció: el volumen quedó al máximo
        }
        AlarmScheduler.schedule(context)
    }
}
