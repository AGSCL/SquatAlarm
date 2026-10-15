package com.squatalarm.app.block

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.squatalarm.app.alarm.AlarmPrefs
import com.squatalarm.app.alarm.AlarmService

/**
 * Mientras la alarma suena, detecta el menú de apagado/reinicio (por clase o por sus textos
 * "Apagar"/"Reiniciar"/…) y lo cierra con Atrás. No hace nada el resto del tiempo.
 */
class PowerMenuBlockerService : AccessibilityService() {

    private var lastBackMs = 0L
    private var lastScanMs = 0L
    private var phonePkgs = emptySet<String>()
    private val emergencyPkgs = HashSet<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            info.notificationTimeout = 0L
            serviceInfo = info
        } catch (e: Exception) { }
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!AlarmService.isRunning && !AlarmPrefs(this).ringingActive()) return
        val pkg = event.packageName?.toString() ?: ""
        if (pkg == packageName) return
        val cls = event.className?.toString() ?: ""
        val stateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (stateChange) {
            // Marcador de emergencia del fabricante (p. ej. Samsung no usa com.android.phone): se reconoce por su pantalla.
            if (cls.contains("emergency", ignoreCase = true)) emergencyPkgs += pkg
            phonePkgs = phonePackages() // consulta al sistema: solo al cambiar de ventana, no en cada evento
        }
        if (pkg in phonePkgs || pkg.contains("incallui")) {
            // Marcador o llamada (también la de emergencia): la alarma sigue sonando pero no los tapa.
            AlarmService.dialerPauseUntil = SystemClock.elapsedRealtime() + AlarmService.DIALER_PAUSE_MS
            return
        }
        // Salió del teléfono a otra app (no la barra del sistema ni el teclado): la alarma vuelve al frente.
        if (stateChange && pkg != "com.android.systemui" && !cls.contains("SoftInputWindow")) {
            AlarmService.dialerPauseUntil = 0L
        }

        var powerMenu = CLASS_HINTS.any { cls.contains(it, ignoreCase = true) }

        if (!powerMenu) {
            val now = SystemClock.elapsedRealtime()
            if (stateChange || now - lastScanMs > SCAN_EVERY_MS) {
                lastScanMs = now
                powerMenu = keywordHits(event) >= 2 // el menú siempre trae al menos Apagar + Reiniciar
            }
        }
        if (powerMenu) dismissPowerMenu()
    }

    /** Marcador, llamada y marcador de emergencia (AOSP: com.android.phone). */
    private fun phonePackages(): Set<String> = try {
        setOfNotNull("com.android.phone", getSystemService(TelecomManager::class.java)?.defaultDialerPackage) + emergencyPkgs
    } catch (e: Exception) {
        setOf("com.android.phone") + emergencyPkgs
    }

    private fun keywordHits(event: AccessibilityEvent): Int {
        val hits = HashSet<String>()
        val budget = intArrayOf(MAX_NODES)
        try {
            collect(event.source, hits, budget)
            if (hits.size < 2) collect(rootInActiveWindow, hits, budget)
            if (hits.size < 2) for (w in windows) {
                if (hits.size >= 2 || budget[0] <= 0) break
                collect(w.root, hits, budget)
            }
        } catch (e: Exception) { }
        return hits.size
    }

    private fun collect(node: AccessibilityNodeInfo?, hits: MutableSet<String>, budget: IntArray) {
        if (node == null || budget[0] <= 0) return
        budget[0]--
        for (t in arrayOf<CharSequence?>(node.text, node.contentDescription, node.viewIdResourceName)) {
            if (t == null) continue
            val s = t.toString().lowercase()
            for (k in KEYWORDS) if (s.contains(k)) hits.add(k)
        }
        for (i in 0 until node.childCount) {
            if (budget[0] <= 0 || hits.size >= 2) return
            collect(node.getChild(i), hits, budget)
        }
    }

    private fun dismissPowerMenu() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackMs < 150L) return
        lastBackMs = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        AlarmService.bringToFront(this, repostNotification = false)
    }

    companion object {
        private const val MAX_NODES = 250
        private const val SCAN_EVERY_MS = 250L
        private val CLASS_HINTS = listOf("GlobalActions", "PowerMenu", "ShutdownMenu", "PowerOff")
        private val KEYWORDS = listOf(
            "power off", "apagar", "shut down", "shutdown", "switch off", "turn off",
            "restart", "reiniciar", "reinicio", "reboot",
            "lockdown", "bloqueo total", "safe mode", "modo seguro",
            "global_actions", "globalactions", "power_menu", "powermenu",
            "desligar", "éteindre", "ausschalten", "spegni",
        )
    }
}
