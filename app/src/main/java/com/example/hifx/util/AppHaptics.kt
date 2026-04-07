package com.example.hifx.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import com.example.hifx.audio.AudioEngine

object AppHaptics {
    fun click(view: View?) {
        val context = view?.context ?: return
        click(context)
    }

    fun click(context: Context) {
        if (!AudioEngine.isHapticFeedbackEnabled()) {
            return
        }
        val vibrator = getVibrator(context) ?: return
        if (!vibrator.hasVibrator()) {
            return
        }
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    vibrator.vibrate(VibrationEffect.createOneShot(12L, 70))
                }

                else -> {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(12L)
                }
            }
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
