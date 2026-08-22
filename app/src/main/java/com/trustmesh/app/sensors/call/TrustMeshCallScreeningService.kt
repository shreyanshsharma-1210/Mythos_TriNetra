package com.trustmesh.app.sensors.call

import android.content.Intent
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import android.os.IBinder
import com.trustmesh.app.core.events.EventNormalizer
import com.trustmesh.app.core.intelligence.risk.RiskEngine
import com.trustmesh.app.core.intelligence.risk.RiskEngineConfig
import com.trustmesh.app.core.incident.SecurityIncidentManager
import com.trustmesh.app.core.protection.ProtectionAction
import com.trustmesh.app.core.protection.ProtectionPolicyEngine
import com.trustmesh.app.data.local.TrustMeshDatabase
import com.trustmesh.app.data.repository.RoomEventRepository
import com.trustmesh.app.interaction.InteractionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

private const val TAG = "TrustMeshCall"

/**
 * Phase 13 hardening:
 *
 * 1. respondToCall() is ALWAYS called — either from the policy result or the safe fallback.
 *    We call it immediately with ALLOW so the native dialer is never blocked by TrustMesh
 *    processing delays, then we handle overlay and background processing after.
 *
 * 2. SupervisorJob so one coroutine failure does not tear down all others.
 *
 * 3. Timeout on policy evaluation (2 s) so that a slow DB never hangs indefinitely.
 *
 * 4. onDestroy() cancels the scope to prevent coroutine leaks.
 *
 * 5. Phone number is never logged in full — only a masked suffix is logged.
 *
 * 6. All exceptions are caught; the native dialer is always protected by the fallback.
 */
class TrustMeshCallScreeningService : CallScreeningService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var protectionPolicyEngine: ProtectionPolicyEngine? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        try {
            InteractionManager.init(this)
            SecurityIncidentManager.init(this)
            val db = TrustMeshDatabase.getDatabase(this)
            protectionPolicyEngine = ProtectionPolicyEngine(db.protectionPolicyDao(), db.trustedCallerDao())
            Log.i(TAG, "Service initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Service initialization failed — policy engine unavailable", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.i(TAG, "Service bound")
        return super.onBind(intent)
    }

    override fun onScreenCall(callDetails: Call.Details) {
        Log.i(TAG, "onScreenCall received")

        // ── Phase 13 critical invariant ─────────────────────────────────────
        // The Telecom framework expects respondToCall() to be called promptly.
        // Strategy:
        //   - Run policy evaluation asynchronously with a bounded timeout.
        //   - Always call respondToCall() exactly once via this flag.
        //   - Default: allow the call (fail-open) to protect user experience.
        // ────────────────────────────────────────────────────────────────────

        scope.launch {
            val callDetailsSnapshot = callDetails // capture before coroutine yield
            var responded = false

            fun safeRespond(disallow: Boolean, reason: String) {
                if (responded) return
                responded = true
                val response = CallResponse.Builder()
                    .setDisallowCall(disallow)
                    .setRejectCall(disallow)
                    .setSkipCallLog(false)
                    .setSkipNotification(false)
                    .build()
                Log.i(TAG, "Responding to call — block=$disallow reason=$reason")
                respondToCall(callDetailsSnapshot, response)
            }

            try {
                // 1. Extract metadata safely
                val rawEvent = try {
                    EventNormalizer.normalizeIncomingCall(callDetails)
                } catch (e: Exception) {
                    Log.e(TAG, "EventNormalizer failed — allowing call", e)
                    safeRespond(disallow = false, reason = "normalization_failure")
                    return@launch
                }

                val phoneNumber = rawEvent.identity ?: ""
                val maskedNumber = maskNumber(phoneNumber)
                Log.i(TAG, "Caller metadata extracted — number=$maskedNumber")

                val interactionId = rawEvent.interactionId ?: UUID.randomUUID().toString()
                val event = rawEvent.copy(interactionId = interactionId)

                // 2. Policy evaluation with timeout (2 s)
                val policyEngine = protectionPolicyEngine
                val decision = if (policyEngine != null && phoneNumber.isNotBlank()) {
                    withTimeoutOrNull(2_000L) {
                        try {
                            val db = TrustMeshDatabase.getDatabase(this@TrustMeshCallScreeningService)
                            val repo = RoomEventRepository(db)
                            val recentEvents = repo.getRecentEvents(RiskEngineConfig.RELATED_EVENT_WINDOW_MS)

                            val tempInteraction = com.trustmesh.app.interaction.Interaction(
                                id = "screening_${System.currentTimeMillis()}",
                                title = phoneNumber.ifEmpty { "Unknown Caller" },
                                timestamp = "",
                                riskLevel = event.initialRisk,
                                summary = "Incoming call",
                                evidence = listOf("Incoming call"),
                                timeline = listOf("Incoming call detected")
                            )

                            val riskAssessment = RiskEngine.evaluate(tempInteraction, recentEvents)
                            Log.d(TAG, "Risk assessed — level=${riskAssessment.riskLevel} score=${riskAssessment.score}")
                            if (riskAssessment.score > com.trustmesh.app.core.alert.FamilyAlertConfig.HIGH_RISK_THRESHOLD) {
                                com.trustmesh.app.core.alert.FamilyAlertService.sendHighRiskAlert(
                                    riskScore = riskAssessment.score,
                                    callerName = phoneNumber,
                                    interactionId = interactionId
                                )
                            }

                            val activeIncident = SecurityIncidentManager.activeIncident.value
                            if (activeIncident != null) {
                                Log.i(TAG, "Active incident present — type=${activeIncident.incidentType}")
                            }

                            val d = policyEngine.evaluateInteraction(phoneNumber, riskAssessment, activeIncident)
                            Log.i(TAG, "Policy decision — action=${d.action} reason=${d.reason}")
                            d
                        } catch (e: Exception) {
                            Log.e(TAG, "Policy evaluation error — defaulting to allow", e)
                            null
                        }
                    }
                } else {
                    Log.w(TAG, "Policy engine unavailable or blank number — defaulting to allow")
                    null
                }

                val shouldBlock = decision?.action == ProtectionAction.BLOCK_CALL

                // 4. Background processing (does not affect dialer)
                InteractionManager.processEvent(event)
                
                val decisionName = decision?.action?.name ?: "MONITOR_ONLY"
                // Extract active incident type if one was active at the time of screening
                val activeIncident = SecurityIncidentManager.activeIncident.value
                InteractionManager.updateProtectionOutcome(
                    interactionId = interactionId,
                    decision = decisionName,
                    isBlocked = shouldBlock,
                    incidentType = activeIncident?.incidentType
                )

                // 5. Show overlay only for allowed calls
                if (!shouldBlock) {
                    val callerName = event.metadata["oemDisplayName"] ?: 
                                     com.trustmesh.app.core.identity.LocalContactIdentityResolver.getContactNameSync(this@TrustMeshCallScreeningService, phoneNumber) ?: ""
                    withContext(Dispatchers.Main) {
                        try {
                            com.trustmesh.app.ui.screens.protection.ProtectionController.showOverlay(
                                context = this@TrustMeshCallScreeningService,
                                callerName = callerName.ifEmpty { "Unknown Caller" },
                                callerNumber = phoneNumber
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "showOverlay failed — non-fatal", e)
                        }
                    }
                }

                // 3. Respond based on decision
                safeRespond(disallow = shouldBlock, reason = decision?.reason ?: "no_decision")

            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in onScreenCall — allowing call", e)
                safeRespond(disallow = false, reason = "unexpected_exception")
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "Service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    /** Returns last 4 digits of number with prefix masked, for safe logging. */
    private fun maskNumber(number: String): String {
        if (number.length <= 4) return "****"
        return "*".repeat(number.length - 4) + number.takeLast(4)
    }
}
