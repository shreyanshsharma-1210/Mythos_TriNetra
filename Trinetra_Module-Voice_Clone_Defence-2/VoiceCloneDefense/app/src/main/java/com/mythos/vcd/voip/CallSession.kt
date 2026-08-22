package com.mythos.vcd.voip

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.mythos.vcd.VcdApp
import com.mythos.vcd.audio.AudioConstants
import com.mythos.vcd.pipeline.FusionThresholds
import com.mythos.vcd.pipeline.SessionScores
import com.mythos.vcd.pipeline.Voiceprint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.io.Closeable
import java.net.Socket

/**
 * One VoIP call, from dialling to hang-up.
 *
 * The handshake is deliberately a real call handshake rather than an immediate SDP exchange:
 *
 *   caller  -- invite{name} -->  callee        callee rings
 *   caller  <-- accept ---------  callee        (or decline, and it stops here)
 *   caller  -- offer ---------->  callee
 *   caller  <-- answer ---------  callee
 *   both    <-- ice ----------->  both
 *
 * The first version skipped straight to the offer, which meant WebRTC negotiated before anybody had
 * agreed to talk — the microphone would open on the receiving device before its owner had answered.
 * Ringing first is not just presentation; it is the difference between a call and a hot mic.
 *
 * Scoring logic lives where it always did. [com.mythos.vcd.pipeline.VerificationPipeline] and
 * [com.mythos.vcd.pipeline.Fusion] are used exactly as Test Mode uses them.
 */
class CallSession(
    private val app: VcdApp,
    private val scope: CoroutineScope,
    private val localName: String,
    private val onState: ((CallState) -> CallState) -> Unit,
) : Closeable {

    private val engine = WebRtcEngine(app)
    private val adapter = RemoteAudioAdapter()
    private val signaling = SignalingClient(scope)
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var scoringJob: Job? = null
    private var diagnosticsJob: Job? = null
    private var ringTimeoutJob: Job? = null

    private var voiceprints: List<Voiceprint> = emptyList()
    private var baselineSynthetic: Float? = null
    private var contactName: String? = null
    private var contactId: Long? = null

    private val pendingCandidates = mutableListOf<IceCandidate>()
    @Volatile private var remoteDescriptionSet = false
    @Volatile private var closed = false
    @Volatile private var stage: CallStage = CallStage.IDLE
    @Volatile private var role: CallRole = CallRole.CALLER
    @Volatile private var remoteHost: String? = null

    /** Fired when an inbound invite arrives, so the manager can start ringing. */
    var onRinging: ((String) -> Unit)? = null

    // ---------------------------------------------------------------- outgoing

    fun placeCall(peer: PeerDiscovery.Peer, contactId: Long?) {
        role = CallRole.CALLER
        this.contactId = contactId
        val startedAt = System.currentTimeMillis()
        adapter.markCallStarted(startedAt)
        setStage(CallStage.DIALLING)

        onState {
            CallState(
                stage = CallStage.DIALLING,
                role = CallRole.CALLER,
                remoteName = peer.name,
                remoteAddress = peer.address,
                startedAtMs = startedAt,
                contactId = contactId,
            )
        }

        wireSignaling()
        scope.launch {
            loadContact(contactId) ?: return@launch
            if (!signaling.connect(peer.address)) return@launch
            signaling.send("invite") { put("name", localName) }
            setStage(CallStage.RINGING_OUT)
            onState { it.copy(stage = CallStage.RINGING_OUT) }
            startRingTimeout()
            observeDiagnostics()
        }
    }

    // ---------------------------------------------------------------- incoming

    /**
     * Adopts an inbound socket. The call does not become visible to the user until the caller's
     * invite arrives, so a stray connection cannot make the phone ring.
     */
    fun adoptIncoming(socket: Socket, contactId: Long?) {
        role = CallRole.CALLEE
        this.contactId = contactId
        adapter.markCallStarted(System.currentTimeMillis())
        remoteHost = socket.inetAddress?.hostAddress
        wireSignaling()
        signaling.adopt(socket)
    }

    private fun onInvite(callerName: String) {
        if (stage != CallStage.IDLE) return
        setStage(CallStage.INCOMING)
        onState {
            CallState(
                stage = CallStage.INCOMING,
                role = CallRole.CALLEE,
                remoteName = callerName,
                remoteAddress = remoteHost,
                startedAtMs = System.currentTimeMillis(),
                contactId = contactId,
            )
        }
        scope.launch {
            loadContact(contactId)
            observeDiagnostics()
        }
        startRingTimeout()
        onRinging?.invoke(callerName)
    }

    fun answer() {
        if (stage != CallStage.INCOMING) return
        cancelRingTimeout()
        signaling.send("accept") { put("name", localName) }
        setStage(CallStage.CONNECTING)
        onState { it.copy(stage = CallStage.CONNECTING) }
        // The microphone opens here and nowhere earlier: this is the moment consent exists.
        if (!startEngine()) return
    }

    fun decline() {
        if (stage != CallStage.INCOMING) return
        cancelRingTimeout()
        signaling.send("decline") {}
        endWith(CallEnding.DECLINED, "Call declined")
    }

    // ---------------------------------------------------------------- shared

    /** Returns null and fails the call when a requested voiceprint cannot be loaded. */
    private suspend fun loadContact(contactId: Long?): Unit? {
        if (contactId == null) return Unit
        val contact = app.contacts.get(contactId)
        contactName = contact?.name
        baselineSynthetic = contact?.baselineSynthetic
        voiceprints = app.contacts.loadVoiceprints(contactId)
        onState { it.copy(contactName = contactName, baselineSynthetic = baselineSynthetic) }
        if (voiceprints.isEmpty()) {
            fail("The stored voiceprint for ${contactName ?: "that contact"} could not be loaded.")
            return null
        }
        return Unit
    }

    private fun startEngine(): Boolean {
        engine.remoteSink = adapter
        engine.listener = engineListener
        return runCatching { engine.start() }
            .onSuccess { routeAudio(speakerphone = true) }
            .onFailure { fail("WebRTC failed to start: ${it.message}") }
            .isSuccess
    }

    private fun wireSignaling() {
        signaling.onConnected = { /* transport up; the invite carries the meaning */ }
        signaling.onClosed = { reason ->
            if (!closed) {
                when (stage) {
                    CallStage.CONNECTED -> endWith(CallEnding.HUNG_UP, null)
                    CallStage.INCOMING -> endWith(CallEnding.MISSED, null)
                    CallStage.IDLE, CallStage.ENDED, CallStage.FAILED -> Unit
                    else -> fail(reason ?: "The call ended before it connected.")
                }
            }
        }
        signaling.onMessage = ::handleSignal
    }

    private fun handleSignal(message: SignalingClient.Message) {
        when (message.type) {
            // Callee side: somebody is calling. Nothing has opened the microphone yet.
            "invite" -> {
                if (role != CallRole.CALLEE) return
                onInvite(message.payload.optString("name").ifBlank { "Unknown caller" })
            }

            // Caller side: they picked up.
            "accept" -> {
                if (role != CallRole.CALLER || stage != CallStage.RINGING_OUT) return
                cancelRingTimeout()
                setStage(CallStage.CONNECTING)
                onState { it.copy(stage = CallStage.CONNECTING) }
                if (!startEngine()) return
                engine.createOffer { sdp -> signaling.send("offer") { put("sdp", sdp.description) } }
            }

            "decline" -> {
                if (role != CallRole.CALLER) return
                cancelRingTimeout()
                endWith(CallEnding.DECLINED, null)
            }

            "offer" -> {
                if (role != CallRole.CALLEE) return
                val sdp = SessionDescription(SessionDescription.Type.OFFER, message.payload.optString("sdp"))
                engine.setRemoteDescription(sdp) {
                    drainCandidates()
                    engine.createAnswer { answer ->
                        signaling.send("answer") { put("sdp", answer.description) }
                    }
                }
            }

            "answer" -> {
                if (role != CallRole.CALLER) return
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, message.payload.optString("sdp"))
                engine.setRemoteDescription(sdp) { drainCandidates() }
            }

            "ice" -> {
                val candidate = IceCandidate(
                    message.payload.optString("sdpMid"),
                    message.payload.optInt("sdpMLineIndex"),
                    message.payload.optString("candidate"),
                )
                if (remoteDescriptionSet) {
                    engine.addIceCandidate(candidate)
                } else {
                    synchronized(pendingCandidates) { pendingCandidates += candidate }
                }
            }

            "bye" -> endWith(CallEnding.HUNG_UP, null)
        }
    }

    private fun drainCandidates() {
        remoteDescriptionSet = true
        val queued = synchronized(pendingCandidates) {
            val copy = pendingCandidates.toList()
            pendingCandidates.clear()
            copy
        }
        queued.forEach(engine::addIceCandidate)
    }

    private val engineListener = object : WebRtcEngine.Listener {
        override fun onLocalIceCandidate(candidate: IceCandidate) {
            signaling.send("ice") {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    setStage(CallStage.CONNECTED)
                    onState {
                        it.copy(
                            stage = CallStage.CONNECTED,
                            connectedAtMs = it.connectedAtMs ?: System.currentTimeMillis(),
                        )
                    }
                    startScoring()
                }

                PeerConnection.PeerConnectionState.FAILED ->
                    fail("The media connection failed. Both devices must be on the same network.")

                PeerConnection.PeerConnectionState.DISCONNECTED,
                PeerConnection.PeerConnectionState.CLOSED -> endWith(CallEnding.HUNG_UP, null)

                else -> Unit
            }
        }

        override fun onRemoteAudioTrack(track: org.webrtc.AudioTrack) {
            Log.i(TAG, "remote audio track ${track.id()} attached")
        }

        override fun onFailure(message: String) = fail(message)
    }

    /**
     * The scoring loop. Structurally identical to the microphone path, because it is the same
     * pipeline; only the adapter that produced the window differs.
     */
    private fun startScoring() {
        if (scoringJob != null) return
        val pipeline = app.models.pipelineOrNull()
        if (pipeline == null) {
            onState {
                it.copy(
                    verifying = false,
                    error = "The on-device models are not available, so this call cannot be checked.",
                )
            }
            return
        }

        onState { it.copy(verifying = true) }

        scoringJob = scope.launch(Dispatchers.Default) {
            var windowIndex = 0L
            while (isActive && !closed) {
                val window = adapter.latestWindow(windowIndex * AudioConstants.LIVE_HOP_SAMPLES)
                if (window == null) {
                    delay(POLL_MS)
                    continue
                }
                val newestSampleAt = System.currentTimeMillis()
                val analysis = pipeline.analyze(
                    window = window,
                    voiceprints = voiceprints,
                    contactName = contactName,
                    thresholds = FusionThresholds.PROVISIONAL,
                )
                adapter.recordWindowAnalysed(
                    inferenceMs = analysis.inferenceMillis,
                    speechToResultMs = System.currentTimeMillis() - newestSampleAt,
                )
                onState {
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

    private fun observeDiagnostics() {
        if (diagnosticsJob != null) return
        diagnosticsJob = scope.launch {
            adapter.diagnostics.collect { d -> onState { it.copy(diagnostics = d) } }
        }
    }

    /** A call nobody answers should stop ringing, not ring until the battery dies. */
    private fun startRingTimeout() {
        ringTimeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            if (stage == CallStage.RINGING_OUT) {
                signaling.send("bye") {}
                endWith(CallEnding.UNANSWERED, null)
            } else if (stage == CallStage.INCOMING) {
                endWith(CallEnding.MISSED, null)
            }
        }
    }

    private fun cancelRingTimeout() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
    }

    fun setMuted(muted: Boolean) {
        engine.setMicrophoneEnabled(!muted)
        onState { it.copy(muted = muted) }
    }

    fun setSpeakerphone(on: Boolean) {
        routeAudio(on)
        onState { it.copy(speakerphone = on) }
    }

    private fun routeAudio(speakerphone: Boolean) {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = speakerphone
        }.onFailure { Log.w(TAG, "could not set audio route", it) }
    }

    private fun setStage(next: CallStage) {
        stage = next
    }

    private fun fail(message: String) {
        if (closed) return
        Log.w(TAG, message)
        setStage(CallStage.FAILED)
        onState {
            it.copy(
                stage = CallStage.FAILED,
                ending = CallEnding.FAILED,
                error = message,
                endedAtMs = System.currentTimeMillis(),
                verifying = false,
            )
        }
        close()
    }

    private fun endWith(ending: CallEnding, note: String?) {
        if (closed || stage == CallStage.ENDED || stage == CallStage.FAILED) return
        setStage(CallStage.ENDED)
        onState {
            it.copy(
                stage = CallStage.ENDED,
                ending = ending,
                endedAtMs = it.endedAtMs ?: System.currentTimeMillis(),
                verifying = false,
                error = note,
            )
        }
        close()
    }

    /** User-initiated hang-up. */
    fun hangUp() {
        if (closed) return
        signaling.send("bye") {}
        endWith(if (stage == CallStage.CONNECTED) CallEnding.HUNG_UP else CallEnding.UNANSWERED, null)
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelRingTimeout()
        scoringJob?.cancel()
        scoringJob = null
        diagnosticsJob?.cancel()
        diagnosticsJob = null
        runCatching { signaling.close() }
        runCatching { engine.close() }
        // Raw remote audio is scrubbed the moment the call ends; only derived scores remain.
        adapter.zeroize()
        voiceprints = emptyList()
        baselineSynthetic = null
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
    }

    private companion object {
        const val TAG = "CallSession"
        const val POLL_MS = 250L
        const val RING_TIMEOUT_MS = 45_000L

        /** One score every 3 s, matching the microphone path exactly. */
        const val HOP_MS = (AudioConstants.LIVE_HOP_SAMPLES * 1000L) / AudioConstants.SAMPLE_RATE
    }
}
