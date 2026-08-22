package com.trustmesh.app.callaudio.webrtc

import android.content.Context
import android.util.Log
import com.trustmesh.app.core.intelligence.groq.GroqIntelligenceClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Collects [WebRtcSttBridge.SttChunk] finals, batches every ~[BATCH_WINDOW_MS] of transcript,
 * then fires [GroqIntelligenceClient] to run L3/L4/L5 analysis on the live call audio.
 *
 * The Groq call is cheap (< 1 s on LTE) and fires every 10 s in steady state — well within
 * Groq's free tier token budget for a typical call.
 */
class GroqLiveAnalyzer(
    private val context: Context,
    private val remoteName: String,
    private val scope: CoroutineScope,
) {
    private val _result = MutableStateFlow<GroqLiveResult?>(null)
    val result: StateFlow<GroqLiveResult?> = _result.asStateFlow()

    private val transcriptBuffer = StringBuilder()
    private val recentSummaries = ArrayDeque<String>(3)
    private var batchJob: Job? = null
    private var collectorJob: Job? = null

    fun start(chunks: kotlinx.coroutines.flow.SharedFlow<WebRtcSttBridge.SttChunk>) {
        if (collectorJob?.isActive == true) return

        // Collect finals into the rolling buffer
        collectorJob = scope.launch {
            chunks.collect { chunk ->
                if (chunk.isFinal && chunk.text.isNotBlank()) {
                    synchronized(transcriptBuffer) {
                        transcriptBuffer.append(chunk.text).append(" ")
                        // Keep rolling ~60 s worth of text (rough cap: 1500 chars)
                        if (transcriptBuffer.length > 1500) {
                            transcriptBuffer.delete(0, transcriptBuffer.length - 1500)
                        }
                    }
                }
            }
        }

        // Fire Groq every BATCH_WINDOW_MS
        batchJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(BATCH_WINDOW_MS)
                val window = synchronized(transcriptBuffer) { transcriptBuffer.toString().trim() }
                if (window.isBlank()) continue
                analyze(window)
            }
        }
    }

    fun stop() {
        collectorJob?.cancel()
        batchJob?.cancel()
        collectorJob = null
        batchJob = null
    }

    private suspend fun analyze(transcriptWindow: String) {
        runCatching {
            val groq = GroqIntelligenceClient.analyzeContent(
                context = context,
                title = "WebRTC Live Call with $remoteName",
                text = transcriptWindow,
                callerIdentity = remoteName,
                recentTimeline = recentSummaries.toList()
            )

            val result = GroqLiveResult(
                groq = groq,
                transcriptWindow = transcriptWindow,
                analyzedAtMs = System.currentTimeMillis(),
                wordsHeard = groq.keySuspiciousPhrases,
                tactics = groq.psychologicalTriggers,
                intent = groq.scamCategory.toHumanLabel(),
                riskScore = groq.riskScore
            )

            recentSummaries.addLast(groq.summaryReasoning)
            if (recentSummaries.size > 3) recentSummaries.removeFirst()

            _result.value = result
            Log.d(TAG, "Groq L3/L4/L5: score=${groq.riskScore} intent=${groq.scamCategory}")
        }.onFailure { Log.e(TAG, "Groq analysis failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "GroqLiveAnalyzer"
        private const val BATCH_WINDOW_MS = 10_000L
    }
}

fun String.toHumanLabel(): String = when (this) {
    "FINANCIAL_FRAUD"          -> "Financial Fraud"
    "OTP_THEFT"                -> "OTP Theft"
    "GOVERNMENT_IMPERSONATION" -> "Govt. Impersonation"
    "REMOTE_ACCESS"            -> "Remote Access Scam"
    "PARCEL_SCAM"              -> "Parcel Scam"
    "UTILITY_SCAM"             -> "Utility Scam"
    "BENIGN"                   -> "No Threat"
    else                       -> this.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
}
