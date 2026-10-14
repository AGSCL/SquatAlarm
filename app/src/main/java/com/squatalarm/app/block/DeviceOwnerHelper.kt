package com.squatalarm.app.block

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build

class AdminReceiver : DeviceAdminReceiver()

/**
 * Opcional. Si la app es "device owner" (ver README), la pantalla de alarma entra en modo kiosco
 * sin diálogo y el sistema desactiva por completo el menú de apagado, la barra de estado y Recientes.
 */
object DeviceOwnerHelper {
    fun setupLockTaskIfOwner(context: Context) {
        try {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            val admin = ComponentName(context, AdminReceiver::class.java)
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
            if (Build.VERSION.SDK_INT >= 28) {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
        } catch (e: Exception) { }
    }
}
