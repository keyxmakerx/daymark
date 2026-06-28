package com.daymark.app.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Gentle haptic cues — essential when the phone is on the chest / eyes closed and the screen can't
 * be seen (e.g. the breathing pacer, or confirming the breathing capture started/finished).
 */
object Haptics {

    private fun vibrator(context: Context): Vibrator? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        }
        else -> {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }?.takeIf { it.hasVibrator() }

    /** A single soft pulse (e.g. the in-breath). */
    fun pulse(context: Context, durationMs: Long = 70, amplitude: Int = 90) {
        val v = vibrator(context) ?: return
        v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255)))
    }

    /** Two soft pulses (e.g. the out-breath / completion), to distinguish from a single pulse. */
    fun doublePulse(context: Context) {
        val v = vibrator(context) ?: return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 90, 60), intArrayOf(0, 90, 0, 90), -1))
    }
}
