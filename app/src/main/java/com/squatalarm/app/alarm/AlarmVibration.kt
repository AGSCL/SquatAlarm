package com.squatalarm.app.alarm

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Vibración de la alarma, con uso «alarma» en todas las versiones. */
object AlarmVibration {
    /** Patrones (apagado, encendido, apagado, …); períodos de 2 s y 1 s: el vigilante los relanza cada 4 s sin cortes raros. */
    val SOFT = longArrayOf(0L, 400L, 1600L)
    val LOUD = longArrayOf(0L, 700L, 300L)

    fun vibrator(context: Context): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /**
     * [repeat] = índice desde donde repetir (-1 = una vez). Sin el uso «alarma», Android ≤ 12 la ignora con ahorro de
     * batería y la corta al apagarse la pantalla o con cualquier vibración de toque.
     */
    fun vibrate(context: Context, pattern: LongArray, repeat: Int) {
        val v = vibrator(context) ?: return
        try {
            val effect = VibrationEffect.createWaveform(pattern, repeat)
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                @Suppress("DEPRECATION")
                v.vibrate(effect, attrs)
            }
        } catch (e: Exception) { }
    }

    fun cancel(context: Context) {
        try { vibrator(context)?.cancel() } catch (e: Exception) { }
    }
}
