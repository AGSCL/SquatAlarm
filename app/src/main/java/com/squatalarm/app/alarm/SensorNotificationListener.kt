package com.squatalarm.app.alarm

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Misión «ir hasta el sensor»: apaga la alarma cuando llega una notificación NUEVA (posterior al
 * inicio de la alarma) de la app y con el texto elegidos (p. ej. SwitchBot «Movimiento detectado»).
 * No guarda ni envía nada: la app no tiene permiso de Internet.
 */
class SensorNotificationListener : NotificationListenerService() {

    class Seen(val pkg: String, val title: String, val text: String, val postTime: Long)

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val seen = read(sbn) ?: return
        picker?.invoke(seen)
        val prefs = AlarmPrefs(this)
        if (prefs.isRinging && prefs.mission == AlarmPrefs.MISSION_SENSOR &&
            SensorMatch.matches(seen.pkg, seen.title, seen.text, seen.postTime, prefs.sensorPackage, prefs.sensorText, prefs.ringingSince)
        ) {
            // Queda a la vista en la app: si se apagó «sola», se ve qué notificación fue.
            prefs.sensorLastHit = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) +
                " · ${seen.pkg} · «${"${seen.title} ${seen.text}".trim()}»"
            AlarmService.stop(this) // AlarmActivity lo nota y se cierra
        }
    }

    companion object {
        @Volatile var instance: SensorNotificationListener? = null
        /** Mientras se elige el sensor en la app, recibe cada notificación nueva. */
        @Volatile var picker: ((Seen) -> Unit)? = null

        fun read(sbn: StatusBarNotification): Seen? {
            val n = sbn.notification
            // Persistentes (en curso o de servicio en primer plano): se re-publican en cada actualización y
            // apagarían la alarma apenas empieza. Los avisos de movimiento no lo son.
            val skip = Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE
            if ((n.flags and skip) != 0) return null
            val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) return null
            return Seen(sbn.packageName, title, text, sbn.postTime)
        }

        /** Notificaciones visibles ahora mismo (para elegir el sensor sin esperar una nueva). */
        fun current(): List<Seen> = try {
            val active: Array<StatusBarNotification> = instance?.activeNotifications ?: emptyArray()
            active.mapNotNull { read(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
