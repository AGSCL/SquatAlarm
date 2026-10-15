package com.squatalarm.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = AlarmPrefs(context)
        when (intent.action) {
            ACTION_FIRE -> {
                prefs.isTestRing = false
                if (prefs.repeatDays == 0) prefs.enabled = false
                AlarmScheduler.schedule(context) // deja lista la siguiente repetición (o nada)
                ring(context, prefs)
            }
            ACTION_RING -> ring(context, prefs)
        }
    }

    private fun ring(context: Context, prefs: AlarmPrefs) {
        if (!prefs.ringingActive()) { // un is_ringing viejo apagaría esta alarma en el primer chequeo (4 s)
            prefs.isRinging = true
            prefs.ringingSince = System.currentTimeMillis()
        }
        AlarmService.start(context)
    }

    companion object {
        const val ACTION_FIRE = "com.squatalarm.app.action.FIRE"
        const val ACTION_RING = "com.squatalarm.app.action.RING"
    }
}
