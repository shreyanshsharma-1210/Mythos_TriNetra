package com.trustmesh.app.core.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

private const val TAG = "EmergencyAlarmManager"

/**
 * Manages continuous emergency alarm audio playback and urgent vibration patterns
 * when a TriNetra emergency SMS alert is received.
 */
object EmergencyAlarmManager {

    @Volatile
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var vibrator: Vibrator? = null

    @Volatile
    private var isAlarmActive: Boolean = false

    /**
     * Starts continuous high-priority emergency alarm audio playback and vibration pattern.
     * Safe to call from BroadcastReceiver — uses prepareAsync() so it never blocks.
     */
    @Synchronized
    fun startEmergencyAlarm(context: Context) {
        if (isAlarmActive) {
            Log.d(TAG, "Emergency alarm is already active")
            return
        }
        isAlarmActive = true
        Log.i(TAG, "🚨 TRINETRA EMERGENCY ALARM TRIGGERED — starting sound and vibration")

        val appContext = context.applicationContext

        // 1. Start continuous vibration pattern
        try {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            vibrator = v
            if (v != null && v.hasVibrator()) {
                // Pattern: 0ms delay, 800ms vibrate, 300ms rest, repeat from index 0
                val pattern = longArrayOf(0, 800, 300, 800, 300, 800, 500)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(pattern, 0)
                }
                Log.i(TAG, "Vibration pattern started successfully")
            } else {
                Log.w(TAG, "Vibrator not available on this device")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start vibration: ${e.message}", e)
        }

        // 2. Play continuous alarm using prepareAsync (safe in background/BroadcastReceiver)
        try {
            // Raise alarm stream volume before playing
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.let { am ->
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                // Always set to 80% of max volume so alarm is audible
                am.setStreamVolume(AudioManager.STREAM_ALARM, (maxVol * 0.85).toInt(), 0)
            }

            // Build a robust URI fallback chain for Xiaomi/MIUI and other OEMs
            val alarmUri: Uri? = sequenceOf(
                { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) },
                { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) },
                { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) },
                { Uri.parse("android.resource://com.android.providers.media/audio/alarms/Alarm_Buzzer.ogg") }
            ).map { it() }.firstOrNull { it != null }

            if (alarmUri == null) {
                Log.e(TAG, "No alarm/ringtone URI found — skipping audio playback")
                return
            }

            Log.i(TAG, "Using alarm URI: $alarmUri")

            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(appContext, alarmUri)
            mp.isLooping = true
            mp.setOnPreparedListener { player ->
                player.start()
                Log.i(TAG, "🔊 Alarm sound started playing continuously")
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                isAlarmActive = false
                false
            }
            // prepareAsync() — non-blocking, safe from BroadcastReceiver / background thread
            mp.prepareAsync()
            mediaPlayer = mp

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start alarm MediaPlayer playback: ${e.message}", e)
            isAlarmActive = false
        }
    }

    /**
     * Stops alarm sound and cancels vibration.
     */
    @Synchronized
    fun stopEmergencyAlarm(context: Context? = null) {
        Log.i(TAG, "Stopping emergency alarm and vibration (was active=$isAlarmActive)")

        try {
            mediaPlayer?.let { mp ->
                try {
                    if (mp.isPlaying) mp.stop()
                } catch (_: Exception) {}
                mp.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaPlayer: ${e.message}", e)
        } finally {
            mediaPlayer = null
        }

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling vibration: ${e.message}", e)
        } finally {
            vibrator = null
        }

        isAlarmActive = false
    }

    fun isAlarmActive(): Boolean = isAlarmActive
}
