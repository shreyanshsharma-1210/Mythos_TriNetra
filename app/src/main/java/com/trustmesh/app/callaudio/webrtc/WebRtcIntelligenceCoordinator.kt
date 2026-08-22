package com.trustmesh.app.callaudio.webrtc

import android.content.Context
import android.util.Log
import com.trustmesh.app.vcd.voip.CallStage
import com.trustmesh.app.vcd.voip.CallManager
import com.trustmesh.app.vcd.voip.RemoteAudioAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Singleton that owns the live intelligence pipeline for a WebRTC call.
 *
 * Started by [com.trustmesh.app.vcd.service.VoipCallService] the moment
 * [CallStage.CONNECTED] fires. Stopped when the call ends.
 *
 * Merges three streams into [WebRtcIntelligenceSession.state]:
 *   1. VCD clone scores (already produced by [com.trustmesh.app.vcd.voip.CallSession])
 *   2. [WebRtcSttBridge] — Vosk STT on remote PCM (Hindi + English)
 *   3. [GroqLiveAnalyzer] — L3/L4/L5 on 10-second transcript batches
 */
object WebRtcIntelligenceCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(WebRtcIntelligenceState())
    val state: StateFlow<WebRtcIntelligenceState> = _state.asStateFlow()

    private var sttBridge: WebRtcSttBridge? = null
    private var groqAnalyzer: GroqLiveAnalyzer? = null
    private var collectorsJob: Job? = null

    fun start(context: Context, adapter: RemoteAudioAdapter, remoteName: String) {
        stop() // defensive — clear any previous call

        _state.value = WebRtcIntelligenceState(
            remoteName = remoteName,
            callConnectedAtMs = System.currentTimeMillis(),
            isCallActive = true,
            sttActive = true
        )

        val bridge = WebRtcSttBridge(context, adapter, scope).also { sttBridge = it }
        val groq = GroqLiveAnalyzer(context, remoteName, scope).also { groqAnalyzer = it }

        bridge.start()
        groq.start(bridge.chunks)

        collectorsJob = scope.launch {
            // ── VCD clone scores ───────────────────────────────────────────
            launch {
                CallManager.state.collect { callState ->
                    val scores = callState.scores
                    _state.value = _state.value.copy(
                        cloneScore = scores.smoothedSynthetic,
                        identityMatch = when {
                            scores.smoothedSimilarity == null -> null
                            scores.smoothedSimilarity >= 0.75f -> true
                            else -> false
                        },
                        vcdVerdict = when {
                            scores.smoothedSynthetic == null -> "Analyzing voice…"
                            scores.smoothedSynthetic >= 0.7f -> "⚠ Cloned voice detected"
                            scores.smoothedSynthetic >= 0.4f -> "Possibly synthetic"
                            else -> "Voice appears genuine"
                        },
                        vcdWindowsScored = scores.measuredWindows
                    ).withFusedScore()
                }
            }

            // ── STT transcript ─────────────────────────────────────────────
            launch {
                bridge.chunks.collect { chunk ->
                    val current = _state.value
                    val newFull = (current.fullTranscript + " " + chunk.text).takeLast(2000)
                    _state.value = current.copy(
                        latestTranscriptChunk = chunk.text,
                        fullTranscript = newFull.trim(),
                        transcriptLanguage = chunk.languageCode,
                        sttActive = true
                    ).withFusedScore()
                }
            }

            // ── Groq L3/L4/L5 ─────────────────────────────────────────────
            launch {
                groq.result.collect { liveResult ->
                    if (liveResult == null) return@collect
                    _state.value = _state.value.copy(
                        groqResult = liveResult,
                        wordsHeard = liveResult.wordsHeard,
                        tacticsDetected = liveResult.tactics,
                        inferredIntent = liveResult.intent
                    ).withFusedScore()

                    Log.d(TAG, "Intelligence update: score=${_state.value.fusedRiskScore} " +
                            "level=${_state.value.alertLevel} intent=${liveResult.intent}")
                }
            }
        }
    }

    fun stop() {
        collectorsJob?.cancel()
        collectorsJob = null
        sttBridge?.stop()
        groqAnalyzer?.stop()
        sttBridge = null
        groqAnalyzer = null
        _state.value = WebRtcIntelligenceState()
    }

    private const val TAG = "WebRtcCoordinator"
}
