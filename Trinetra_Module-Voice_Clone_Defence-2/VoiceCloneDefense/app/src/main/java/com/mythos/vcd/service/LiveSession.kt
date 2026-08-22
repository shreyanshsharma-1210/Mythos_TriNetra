package com.mythos.vcd.service

import com.mythos.vcd.audio.MicCapture
import com.mythos.vcd.pipeline.SessionScores
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared observable state for the live verification session.
 *
 * The foreground service writes; the Live Verification screen reads. Keeping it here rather than
 * behind a binder means the UI keeps rendering the last known state across rotation and process
 * lifecycle events without a reconnect dance, and there is exactly one place to look to answer
 * "what does the app currently believe about this call".
 */
object LiveSession {

    data class State(
        val running: Boolean = false,
        val contactId: Long? = null,
        val contactName: String? = null,
        val scores: SessionScores = SessionScores(),
        val mic: MicCapture.State = MicCapture.State(),
        val startedAtMs: Long? = null,
        /**
         * Milliseconds between the disclosure banner appearing and the microphone opening.
         * Negative or null would mean the interlock failed; the UI surfaces it either way so the
         * 1-second requirement is observable on-device rather than asserted in a document.
         */
        val bannerToCaptureMs: Long? = null,
        val fatalError: String? = null,
        /**
         * The synthetic_probability measured on this contact's own enrolment recording, or null if
         * they were enrolled before baselines were taken. Surfaced so the UI can say how much the
         * live synthetic score is worth instead of showing it bare.
         */
        val baselineSynthetic: Float? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    internal fun update(block: (State) -> State) {
        _state.value = block(_state.value)
    }

    internal fun reset() {
        _state.value = State()
    }
}
