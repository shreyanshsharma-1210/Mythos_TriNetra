package com.trustmesh.app.vcd.service

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The hard interlock behind FR-VOICE-CAP-2 and hard constraint 3: capture cannot start unless the
 * disclosure banner is already on screen.
 *
 * This is enforced in code rather than left to screen ordering. The banner composable marks the
 * gate open when it actually draws; [LiveVerificationService] refuses to open the microphone if
 * the gate is shut. A future refactor that reorders navigation, or a deep link that jumps straight
 * into verification, therefore fails closed — the microphone stays off and the user sees why —
 * rather than silently capturing audio with no disclosure on screen.
 */
object DisclosureGate {

    private val _shownAtMs = MutableStateFlow<Long?>(null)

    /** Wall-clock time the banner became visible, or null if it is not currently shown. */
    val shownAtMs: StateFlow<Long?> = _shownAtMs.asStateFlow()

    /** Called by the banner composable the moment it is drawn. Idempotent. */
    fun markShown() {
        if (_shownAtMs.value == null) {
            _shownAtMs.value = System.currentTimeMillis()
            Log.i(TAG, "disclosure banner shown at ${_shownAtMs.value}")
        }
    }

    /** Called when the banner leaves the screen. Any capture still running must stop first. */
    fun markHidden() {
        _shownAtMs.value = null
        Log.i(TAG, "disclosure banner hidden")
    }

    val isOpen: Boolean get() = _shownAtMs.value != null

    /** Milliseconds between the banner appearing and [at]; negative means capture led the banner. */
    fun latencyMsAt(at: Long): Long? = _shownAtMs.value?.let { at - it }

    private const val TAG = "DisclosureGate"
}
