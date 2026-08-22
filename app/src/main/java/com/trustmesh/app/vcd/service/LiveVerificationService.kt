package com.trustmesh.app.vcd.service

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.trustmesh.app.vcd.VcdApp
import com.trustmesh.app.vcd.audio.AudioConstants
import com.trustmesh.app.vcd.audio.AudioRingBuffer
import com.trustmesh.app.vcd.audio.AudioWindow
import com.trustmesh.app.vcd.audio.MicCapture
import com.trustmesh.app.vcd.pipeline.FusionThresholds
import com.trustmesh.app.vcd.pipeline.SessionScores
import com.trustmesh.app.vcd.pipeline.Voiceprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Phase 1 foreground service. Runs only while Live Verification is on screen and only with the
 * disclosure banner already visible.
 *
 * Everything about capture is deliberately loud: a persistent notification the user cannot swipe
 * away, the system's own microphone indicator, and the on-screen banner. There is no code path
 * through this class that opens the microphone quietly.
 */
class LiveVerificationService : LifecycleService() {

    private lateinit var mic: MicCapture
    private val ring = AudioRingBuffer(AudioConstants.WINDOW_SAMPLES * 2)
    private var scoringJob: Job? = null
    private var voiceprints: List<Voiceprint> = emptyList()
    private var baselineSynthetic: Float? = null
    private var micStateJob: Job? = null
    private var bannerWatchJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        mic = MicCapture(this)
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start(
                contactId = intent.getLongExtra(EXTRA_CONTACT_ID, NO_CONTACT)
                    .takeIf { it != NO_CONTACT },
                contactName = intent.getStringExtra(EXTRA_CONTACT_NAME),
            )

            ACTION_STOP -> stopEverything(null)
            else -> stopEverything("Service started without an action.")
        }
        return START_NOT_STICKY
    }

    private fun start(contactId: Long?, contactName: String?) {
        // Interlock: no banner, no microphone. See DisclosureGate.
        val bannerShownAt = DisclosureGate.shownAtMs.value
        if (bannerShownAt == null) {
            stopEverything(
                "Capture was blocked because the disclosure banner was not on screen. " +
                    "This is a safety interlock, not a bug — the microphone never opens without it."
            )
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopEverything("Microphone permission is not granted, so live verification cannot run.")
            return
        }

        val app = application as VcdApp
        val pipeline = app.models.pipelineOrNull()
        if (pipeline == null) {
            stopEverything(
                "The on-device models are not available, so no score can be produced. " +
                    "Test Mode and enrolment will also be unavailable until they are bundled."
            )
            return
        }

        try {
            ServiceCompat.startForeground(
                this,
                Notifications.NOTIFICATION_ID_CAPTURE,
                Notifications.captureNotification(this, contactName),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                },
            )
        } catch (t: Throwable) {
            // Android 14 refuses microphone-type foreground services started from the background.
            stopEverything("Could not start the microphone foreground service: ${t.message}")
            return
        }

        LiveSession.update {
            SessionStart(contactId, contactName, bannerShownAt).applyTo(it)
        }

        lifecycleScope.launch {
            voiceprints = contactId?.let { app.contacts.loadVoiceprints(it) } ?: emptyList()
            baselineSynthetic = contactId?.let { app.contacts.get(it)?.baselineSynthetic }
            LiveSession.update { it.copy(baselineSynthetic = baselineSynthetic) }
            if (contactId != null && voiceprints.isEmpty()) {
                // The user asked to check against a specific person and we cannot. Saying so beats
                // silently degrading into an anonymous spoof-only check they did not ask for.
                stopEverything(
                    "The stored voiceprint for ${contactName ?: "that contact"} could not be " +
                        "loaded, so this call cannot be checked against them."
                )
                return@launch
            }

            val started = mic.start { samples, length -> ring.write(samples, 0, length) }
            val captureOpenedAt = System.currentTimeMillis()
            if (!started) {
                stopEverything(
                    mic.state.value.detail ?: "The microphone could not be opened for this call."
                )
                return@launch
            }

            LiveSession.update {
                it.copy(bannerToCaptureMs = captureOpenedAt - bannerShownAt)
            }
            Log.i(
                TAG,
                "capture opened ${captureOpenedAt - bannerShownAt} ms after the banner appeared",
            )

            // The interlock is continuous, not a one-time check at startup. If the banner ever
            // leaves the screen while the microphone is open — a navigation bug, a refactor that
            // reorders the composition, anything — capture stops immediately rather than
            // continuing undisclosed.
            bannerWatchJob = lifecycleScope.launch {
                DisclosureGate.shownAtMs.collect { shownAt ->
                    if (shownAt == null) {
                        stopEverything(
                            "The disclosure banner left the screen, so capture was stopped. " +
                                "The microphone is never open without it."
                        )
                    }
                }
            }

            micStateJob = lifecycleScope.launch {
                mic.state.collect { micState ->
                    LiveSession.update { it.copy(mic = micState) }
                    // A mid-call capture failure stops the session outright. Continuing to score a
                    // stream we know is broken is exactly the "score from audio we are not
                    // confident about" case Phase 6 forbids.
                    if (micState.failure != null) {
                        stopEverything(micState.detail ?: "Microphone capture failed: ${micState.failure}")
                    }
                }
            }

            scoringJob = lifecycleScope.launch(Dispatchers.Default) {
                var windowIndex = 0L
                while (isActive) {
                    val samples = ring.readLatest(AudioConstants.WINDOW_SAMPLES)
                    if (samples == null) {
                        // Not enough audio yet for a full window. Wait rather than pad.
                        delay(POLL_MS)
                        continue
                    }
                    val window = AudioWindow(
                        samples = samples,
                        startSampleIndex = windowIndex * AudioConstants.LIVE_HOP_SAMPLES,
                        provenance = AudioWindow.Provenance.LIVE_MIC,
                    )
                    val analysis = pipeline.analyze(
                        window = window,
                        voiceprints = voiceprints,
                        contactName = contactName,
                        thresholds = FusionThresholds.PROVISIONAL,
                    )
                    LiveSession.update {
                        it.copy(
                            scores = it.scores.accept(
                                analysis,
                                FusionThresholds.PROVISIONAL,
                                baselineSynthetic,
                            )
                        )
                    }
                    windowIndex++
                    delay(HOP_MS)
                }
            }
        }
    }

    private data class SessionStart(
        val contactId: Long?,
        val contactName: String?,
        val bannerShownAt: Long,
    ) {
        fun applyTo(prev: LiveSession.State) = prev.copy(
            running = true,
            contactId = contactId,
            contactName = contactName,
            scores = SessionScores(),
            startedAtMs = System.currentTimeMillis(),
            bannerToCaptureMs = null,
            fatalError = null,
            // Cleared here and re-read from the database below, so a previous session's contact
            // cannot leave its baseline behind to qualify this one's scores.
            baselineSynthetic = null,
        )
    }

    private fun stopEverything(error: String?) {
        error?.let { Log.w(TAG, it) }
        scoringJob?.cancel()
        scoringJob = null
        micStateJob?.cancel()
        micStateJob = null
        bannerWatchJob?.cancel()
        bannerWatchJob = null
        if (this::mic.isInitialized) mic.stop()
        // Scrub the rolling buffer so captured audio does not sit in the heap after the session.
        ring.zeroize()
        voiceprints = emptyList()
        baselineSynthetic = null

        LiveSession.update { it.copy(running = false, fatalError = error) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)
            ?.cancel(Notifications.NOTIFICATION_ID_CAPTURE)
        stopSelf()
    }

    override fun onDestroy() {
        scoringJob?.cancel()
        micStateJob?.cancel()
        bannerWatchJob?.cancel()
        if (this::mic.isInitialized) mic.stop()
        ring.zeroize()
        LiveSession.update { it.copy(running = false) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LiveVerificationService"
        private const val NO_CONTACT = -1L

        /** Poll interval while waiting for the first full window to accumulate. */
        private const val POLL_MS = 250L

        /** 3 s between scores, each covering the most recent 4.0375 s. */
        private const val HOP_MS =
            (AudioConstants.LIVE_HOP_SAMPLES * 1000L) / AudioConstants.SAMPLE_RATE

        const val ACTION_START = "com.mythos.vcd.action.START_LIVE"
        const val ACTION_STOP = "com.mythos.vcd.action.STOP_LIVE"
        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_CONTACT_NAME = "contact_name"

        fun start(context: Context, contactId: Long?, contactName: String?) {
            val intent = Intent(context, LiveVerificationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONTACT_ID, contactId ?: NO_CONTACT)
                .putExtra(EXTRA_CONTACT_NAME, contactName)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LiveVerificationService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
