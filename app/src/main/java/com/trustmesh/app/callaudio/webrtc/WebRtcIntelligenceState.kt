package com.trustmesh.app.callaudio.webrtc

import com.trustmesh.app.core.intelligence.groq.GroqAnalysisResponse

// ── Data classes ─────────────────────────────────────────────────────────────

data class GroqLiveResult(
    val groq: GroqAnalysisResponse,
    val transcriptWindow: String,
    val analyzedAtMs: Long,
    val wordsHeard: List<String>,    // keySuspiciousPhrases from Groq
    val tactics: List<String>,       // psychologicalTriggers
    val intent: String,              // human-readable scamCategory
    val riskScore: Int
)

enum class AlertLevel(val displayLabel: String) {
    CLEAR("Clear"),
    MONITORING("Monitoring"),
    ELEVATED("Elevated Risk"),
    CRITICAL("Critical Risk")
}

data class WebRtcIntelligenceState(
    val remoteName: String = "",
    val callConnectedAtMs: Long = 0L,
    // ── VCD clone scoring ──────────────────────────────────────────────────
    val cloneScore: Float? = null,          // 0 = genuine, 1 = synthetic
    val identityMatch: Boolean? = null,
    val vcdVerdict: String = "Analyzing voice…",
    val vcdWindowsScored: Int = 0,
    // ── STT transcript ─────────────────────────────────────────────────────
    val latestTranscriptChunk: String = "",  // most recent partial/final
    val fullTranscript: String = "",         // rolling last 60 s
    val transcriptLanguage: String = "",     // "hi" or "en"
    val sttActive: Boolean = false,
    // ── Groq L3/L4/L5 ─────────────────────────────────────────────────────
    val groqResult: GroqLiveResult? = null,
    val wordsHeard: List<String> = emptyList(),
    val tacticsDetected: List<String> = emptyList(),
    val inferredIntent: String = "",
    // ── Fused risk ─────────────────────────────────────────────────────────
    val fusedRiskScore: Int = 0,
    val alertLevel: AlertLevel = AlertLevel.CLEAR,
    // ── Overlay UX ─────────────────────────────────────────────────────────
    val overlayExpanded: Boolean = false,
    val isCallActive: Boolean = false
) {
    /** Weighted fuse: VCD 40% + Groq 60%. VCD is precise but needs ~12 s; Groq fires faster. */
    fun withFusedScore(): WebRtcIntelligenceState {
        val vcdContrib = (cloneScore ?: 0f) * 100f * 0.4f
        val groqContrib = (groqResult?.riskScore ?: 0) * 0.6f
        val fused = (vcdContrib + groqContrib).toInt().coerceIn(0, 100)
        val level = when {
            fused >= 70 -> AlertLevel.CRITICAL
            fused >= 45 -> AlertLevel.ELEVATED
            fused >= 20 -> AlertLevel.MONITORING
            else -> AlertLevel.CLEAR
        }
        return copy(
            fusedRiskScore = fused,
            alertLevel = level,
            overlayExpanded = overlayExpanded || level >= AlertLevel.ELEVATED
        )
    }
}
