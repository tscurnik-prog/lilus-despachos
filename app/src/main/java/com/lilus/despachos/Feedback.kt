package com.lilus.despachos

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Sonidos y vibraciones de confirmación / error. Nunca debe romper el escaneo. */
class Feedback(context: Context) {

    private val tones: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 90)
    } catch (e: RuntimeException) {
        null
    }

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: RuntimeException) {
        null
    }

    fun success() {
        safe { tones?.startTone(ToneGenerator.TONE_PROP_ACK, 150) }
        vibrate(longArrayOf(0, 60))
    }

    fun duplicate() {
        safe { tones?.startTone(ToneGenerator.TONE_SUP_ERROR, 450) }
        vibrate(longArrayOf(0, 180, 120, 180, 120, 180))
    }

    fun warning() {
        safe { tones?.startTone(ToneGenerator.TONE_PROP_NACK, 250) }
        vibrate(longArrayOf(0, 120))
    }

    private fun vibrate(pattern: LongArray) = safe {
        val v = vibrator ?: return@safe
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, -1)
        }
    }

    fun release() = safe { tones?.release() }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (e: RuntimeException) { /* sin sonido/vibración: el escaneo sigue */ }
    }
}
