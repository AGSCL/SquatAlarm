package com.squatalarm.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import com.squatalarm.app.alarm.AlarmService
import com.squatalarm.app.block.DeviceOwnerHelper

class SquatAlarmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            AlarmService.CHANNEL_ID, "Alarma", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alarma sonando (se apaga haciendo sentadillas)"
            setSound(null, null) // el sonido lo reproduce el servicio
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        DeviceOwnerHelper.setupLockTaskIfOwner(this)
    }
}
