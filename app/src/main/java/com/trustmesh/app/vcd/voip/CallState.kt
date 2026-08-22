package com.trustmesh.app.vcd.voip

import com.trustmesh.app.vcd.pipeline.SessionScores

/**
 * Where a VoIP call currently is.
 *
 * The stages mirror what a person would say about a phone call — dialling, ringing, connected —
 * rather than what WebRTC is doing underneath, because that is what the screen has to render.
 * Signalling and ICE states are an implementation detail of [CallSession].
 */
enum class CallStage {
    IDLE,

    /** Opening the connection to the other device. Sub-second on a LAN. */
    DIALLING,

    /** The other device has been invited and is ringing. */
    RINGING_OUT,

    /** Somebody is calling this device and the user has not answered yet. */
    INCOMING,

    /** Answered; ICE and DTLS are still negotiating. */
    CONNECTING,

    /** Media is flowing. */
    CONNECTED,

    ENDED,
    FAILED,
}

/** Which end placed the call. */
enum class CallRole { CALLER, CALLEE }

/** Why a call finished, so the screen can say "Declined" rather than a generic "Ended". */
enum class CallEnding { HUNG_UP, DECLINED, MISSED, UNANSWERED, FAILED }

data class CallState(
    val stage: CallStage = CallStage.IDLE,
    val role: CallRole? = null,

    /** Display name of the other party, as advertised over mDNS. */
    val remoteName: String? = null,
    val remoteAddress: String? = null,

    val startedAtMs: Long? = null,
    val connectedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val ending: CallEnding? = null,

    val muted: Boolean = false,
    val speakerphone: Boolean = true,
    val error: String? = null,

    /** Live audio-path diagnostics. This is what Phase 1 exists to produce. */
    val diagnostics: CallDiagnostics = CallDiagnostics(),

    /** Verification of the *remote* party's voice. Empty until the pipeline has scored a window. */
    val scores: SessionScores = SessionScores(),

    val contactId: Long? = null,
    val contactName: String? = null,
    val baselineSynthetic: Float? = null,
    /** Shared-secret codeword to ask the caller for, if one was set for this contact. */
    val challenge: String? = null,

    /** True once analysis is running, so the UI never implies a check that is not happening. */
    val verifying: Boolean = false,
) {
    /** A call the user is in, or is being asked about. */
    val active: Boolean
        get() = stage == CallStage.DIALLING || stage == CallStage.RINGING_OUT ||
            stage == CallStage.INCOMING || stage == CallStage.CONNECTING ||
            stage == CallStage.CONNECTED

    /** True while the screen should be a full-bleed call UI rather than the dialler. */
    val onCallScreen: Boolean get() = active || stage == CallStage.ENDED || stage == CallStage.FAILED

    val durationSeconds: Long
        get() {
            val start = connectedAtMs ?: return 0
            val end = endedAtMs ?: System.currentTimeMillis()
            return ((end - start) / 1000L).coerceAtLeast(0)
        }

    /** Milliseconds from placing the call to media flowing. */
    val setupMillis: Long?
        get() = if (startedAtMs != null && connectedAtMs != null) connectedAtMs - startedAtMs else null

    /** The line under the name on the call screen. */
    fun statusLine(): String = when (stage) {
        CallStage.IDLE -> ""
        CallStage.DIALLING -> "Calling…"
        CallStage.RINGING_OUT -> "Ringing…"
        CallStage.INCOMING -> "TRINETRA call"
        CallStage.CONNECTING -> "Connecting…"
        CallStage.CONNECTED -> formatDuration(durationSeconds)
        CallStage.ENDED -> when (ending) {
            CallEnding.DECLINED -> "Call declined"
            CallEnding.MISSED -> "Missed call"
            CallEnding.UNANSWERED -> "No answer"
            else -> "Call ended"
        }

        CallStage.FAILED -> "Call failed"
    }

    private fun formatDuration(seconds: Long) = "%d:%02d".format(seconds / 60, seconds % 60)
}
