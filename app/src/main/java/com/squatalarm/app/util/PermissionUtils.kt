package com.squatalarm.app.util

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.squatalarm.app.alarm.AlarmPrefs
import com.squatalarm.app.alarm.SensorMatch
import com.squatalarm.app.block.PowerMenuBlockerService

/** Un permiso o ajuste que la alarma necesita y la pantalla de Ajustes donde se concede. */
data class PermItem(
    val id: String,
    val title: String,
    val description: String,
    val granted: Boolean,
    val intent: Intent,
)

object PermissionUtils {
    const val ID_NOTIFICATIONS = "notifications"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    /** Permisos que Android 13+ bloquea en apps instaladas fuera de una tienda («ajustes restringidos»). */
    val RESTRICTED = setOf("overlay", "accessibility", "listener")

    fun items(context: Context): List<PermItem> {
        val pkgUri = Uri.fromParts("package", context.packageName, null)
        val list = mutableListOf<PermItem>()
        if (Build.VERSION.SDK_INT >= 33) {
            list += PermItem(
                ID_NOTIFICATIONS, "Notificaciones",
                "Necesarias para que la alarma aparezca a pantalla completa.",
                ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                // El ajuste de la app y no el diálogo: tras dos «No permitir», Android deja de mostrar el diálogo.
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        }
        if (Build.VERSION.SDK_INT >= 31) {
            list += PermItem(
                "exact", "Alarmas y recordatorios",
                "Para que suene exactamente a la hora programada.",
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)
            )
        }
        if (Build.VERSION.SDK_INT >= 34) {
            list += PermItem(
                "fsi", "Pantalla completa",
                "Para abrir la alarma sobre la pantalla bloqueada.",
                context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri)
            )
        }
        list += PermItem(
            "overlay", "Mostrar sobre otras apps",
            "Para volver a traer la alarma al frente si intentas salir de ella.",
            Settings.canDrawOverlays(context),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
        )
        list += PermItem(
            "accessibility", "Servicio de accesibilidad",
            "Cierra el menú de apagado mientras suena. Actívalo en Apps instaladas → Alarma Sentadillas.",
            accessibilityEnabled(context),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        )
        if (AlarmPrefs(context).mission == AlarmPrefs.MISSION_SENSOR) {
            list += PermItem(
                "listener", "Acceso a notificaciones",
                "Para saber cuándo el sensor detecta movimiento. Solo se mira la notificación del sensor elegido.",
                listenerEnabled(context),
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            )
        }
        list += PermItem(
            "battery", "Sin restricción de batería",
            "Evita que el sistema detenga la alarma.",
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
        )
        return list
    }

    fun missingCount(context: Context): Int = items(context).count { !it.granted }

    private fun listenerEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /**
     * La misión del sensor solo se usa si está completa (texto de la notificación y acceso concedido) y el
     * teléfono ya se desbloqueó tras encenderse (antes, la app del sensor no corre). Si no, la alarma se apaga
     * con sentadillas; de lo contrario solo el apagado de seguridad podría pararla.
     */
    fun sensorMissionReady(context: Context): Boolean {
        val p = AlarmPrefs(context)
        return p.mission == AlarmPrefs.MISSION_SENSOR && SensorMatch.configured(p.sensorPackage, p.sensorText) && listenerEnabled(context) &&
            context.getSystemService(UserManager::class.java).isUserUnlocked
    }

    private fun accessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val cn = ComponentName(context, PowerMenuBlockerService::class.java)
        val full = cn.flattenToString()
        val short = cn.flattenToShortString()
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }
}
