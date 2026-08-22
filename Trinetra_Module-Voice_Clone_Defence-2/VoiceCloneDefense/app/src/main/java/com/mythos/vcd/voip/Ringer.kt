package com.mythos.vcd.voip

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.io.Closeable

/**
 * Ringtone and vibration for an incoming call, and the ringback tone for an outgoing one.
 *
 * Uses the user's own ringtone rather than a bundled sound. A call that rings with an unfamiliar
 * noise reads as a notification from an app; one that rings with the sound the user has chosen for
 * calls reads as a call. Respects the ringer mode, so a phone on silent stays silent and a phone on
 * vibrate only buzzes.
 */
class Ringer(private val context: Context) : Closeable {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var ringtone: Ringtone? = null
    private var toneGenerator: ToneGenerator? = null

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun startIncoming() {
        stop()
        when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> return
            AudioManager.RINGER_MODE_VIBRATE -> vibratePattern()
            else -> {
                vibratePattern()
                playRingtone()
            }
        }
    }

    /** The tone the caller hears while the other phone is ringing. */
    fun startRingback() {
        stop()
        runCatching {
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, RINGBACK_VOLUME).apply {
                startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        }.onFailure { Log.w(TAG, "ringback unavailable", it) }
    }

    private fun playRingtone() {
        runCatching {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        }.onFailure { Log.w(TAG, "could not play ringtone", it) }
    }

    private fun vibratePattern() {
        val v = vibrator ?: return
        runCatching {
            val timings = longArrayOf(0, 800, 800)
            val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0)
            v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, /* repeat = */ 0))
        }.onFailure { Log.w(TAG, "could not vibrate", it) }
    }

    fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching {
            toneGenerator?.stopTone()
            toneGenerator?.release()
        }
        toneGenerator = null
        runCatching { vibrator?.cancel() }
    }

    override fun close() = stop()

    private companion object {
        const val TAG = "Ringer"
        const val RINGBACK_VOLUME = 60
    }
}
