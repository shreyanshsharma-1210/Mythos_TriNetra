package com.trustmesh.app.core.voicescan

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "VoiceScanController"

/** Where a deep voice-analysis run currently is. */
enum class VoiceScanPhase {
    IDLE,
    BUFFERING,
    ANALYZING,
    SETTLED,
}

enum class VoiceScanVerdict { SYNTHETIC, GENUINE }

data class VoiceScanMarker(
    val label: String,
    val reading: String,
    val flagged: Boolean,
)

data class VoiceScanState(
    val phase: VoiceScanPhase = VoiceScanPhase.IDLE,
    val verdict: VoiceScanVerdict? = null,
    val secondsRemaining: Int = 0,
    val bufferSeconds: Int = 0,
    val riskScore: Int = 0,
    val cloneConfidence: Int = 0,
    val headline: String = "",
    val summary: String = "",
    val markers: List<VoiceScanMarker> = emptyList(),
    val trend: List<Int> = emptyList(),
    val decisionRule: String = "",
) {
    val active: Boolean get() = phase != VoiceScanPhase.IDLE
    val flaggedMarkers: Int get() = markers.count { it.flagged }
    val hasScore: Boolean get() = active && (phase != VoiceScanPhase.BUFFERING || verdict != null)

    fun effectiveRiskLevel(base: com.trustmesh.app.core.events.RiskLevel): com.trustmesh.app.core.events.RiskLevel =
        if (!hasScore) base else when {
            riskScore >= 75 -> com.trustmesh.app.core.events.RiskLevel.CRITICAL
            riskScore >= 50 -> com.trustmesh.app.core.events.RiskLevel.HIGH
            riskScore >= 25 -> com.trustmesh.app.core.events.RiskLevel.ELEVATED
            else -> com.trustmesh.app.core.events.RiskLevel.LOW
        }
}

/**
 * Holds overlay voice-scan state between call sessions.
 *
 * Live clone detection runs through [com.trustmesh.app.vcd.pipeline.VerificationPipeline] on-device;
 * this controller no longer accepts SMS control codes.
 */
object VoiceScanController {

    private val _state = MutableStateFlow(VoiceScanState())
    val state: StateFlow<VoiceScanState> = _state.asStateFlow()

    fun resetForNewCall() {
        Log.i(TAG, "Reset for new call")
        _state.value = VoiceScanState()
    }
}
