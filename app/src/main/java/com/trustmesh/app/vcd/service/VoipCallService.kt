package com.trustmesh.app.vcd.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.trustmesh.app.callaudio.webrtc.WebRtcIntelligenceCoordinator
import com.trustmesh.app.vcd.voip.CallManager
import com.trustmesh.app.vcd.voip.CallStage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps a call alive while the app is not in the foreground, and rings the phone.
 *
 * Two jobs, both of which have to happen outside the UI. WebRTC holds the microphone for the
 * duration of a call, and from Android 11 a background app loses microphone access within seconds —
 * without a foreground service a call would go silent the moment the user checked a message, which
 * would look exactly like the audio bug this whole module exists to route around. And an incoming
 * call has to reach the user when the app is closed and the screen is off, which is what the
 * full-screen-intent notification is for.
 *
 * It owns no call state. [CallManager] holds the call; this service gives it a foreground lifetime
 * and a presence the user cannot miss.
 */
class VoipCallService : LifecycleService() {

    private var watchJob: Job? = null
    private var lastStage: CallStage? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Notifications.ensureChannels(this)

        when (intent?.action) {
            ACTION_START -> start()
            ACTION_ANSWER -> {
                CallManager.answer()
                start()
            }

            ACTION_DECLINE -> {
                CallManager.decline()
                stopEverything()
            }

            ACTION_STOP -> {
                CallManager.hangUp()
                stopEverything()
            }

            else -> stopEverything()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (watchJob != null) return

        val initial = CallManager.state.value
        if (!promote(initial.stage, initial.remoteName)) {
            stopEverything()
            return
        }

        // The service follows the call, not the screen. When the call reaches a terminal state the
        // notification goes away by itself rather than waiting for the UI to tidy up.
        watchJob = lifecycleScope.launch {
            CallManager.state.collect { state ->
                if (!state.active) {
                    WebRtcIntelligenceCoordinator.stop()
                    stopEverything()
                    return@collect
                }
                if (state.stage != lastStage) {
                    promote(state.stage, state.remoteName)
                    // Start the intelligence pipeline the moment audio is flowing
                    if (state.stage == CallStage.CONNECTED) {
                        val session = com.trustmesh.app.vcd.voip.CallManager.currentSession()
                        val adapter = session?.remoteAudioAdapter
                        if (adapter != null) {
                            WebRtcIntelligenceCoordinator.start(
                                context = this@VoipCallService,
                                adapter = adapter,
                                remoteName = state.remoteName ?: "Unknown"
                            )
                        }
                    }
                }
            }
        }
    }

    /** Swaps between the ringing notification and the in-call one as the stage changes. */
    private fun promote(stage: CallStage, peer: String?): Boolean {
        lastStage = stage
        val (id, notification) = when (stage) {
            CallStage.INCOMING ->
                Notifications.NOTIFICATION_ID_INCOMING to
                    Notifications.incomingCallNotification(this, peer)

            CallStage.IDLE, CallStage.ENDED, CallStage.FAILED -> return false

            else ->
                Notifications.NOTIFICATION_ID_CALL to Notifications.callNotification(this, peer)
        }

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        return try {
            ServiceCompat.startForeground(
                this,
                id,
                notification,
                fgsType,
            )
            // Clear whichever of the two notifications is no longer the current one, so a call that
            // has been answered does not leave a stale "incoming" entry behind.
            val stale = if (id == Notifications.NOTIFICATION_ID_CALL) {
                Notifications.NOTIFICATION_ID_INCOMING
            } else {
                Notifications.NOTIFICATION_ID_CALL
            }
            getSystemService(NotificationManager::class.java)?.cancel(stale)
            true
        } catch (t: Throwable) {
            android.util.Log.e("VoipCallService", "startForeground failed", t)
            false
        }
    }

    private fun stopEverything() {
        watchJob?.cancel()
        watchJob = null
        lastStage = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)?.let {
            it.cancel(Notifications.NOTIFICATION_ID_CALL)
            it.cancel(Notifications.NOTIFICATION_ID_INCOMING)
        }
        stopSelf()
    }

    override fun onDestroy() {
        watchJob?.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.mythos.vcd.action.START_CALL"
        const val ACTION_STOP = "com.mythos.vcd.action.STOP_CALL"
        const val ACTION_ANSWER = "com.mythos.vcd.action.ANSWER_CALL"
        const val ACTION_DECLINE = "com.mythos.vcd.action.DECLINE_CALL"

        fun start(context: Context, peer: String?) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VoipCallService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, VoipCallService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
