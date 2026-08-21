package com.trustmesh.app.sensors.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.trustmesh.app.core.events.EventType
import com.trustmesh.app.core.events.EventSource
import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.core.events.SecurityEvent
import com.trustmesh.app.core.intelligence.risk.RiskEngine
import com.trustmesh.app.core.intelligence.risk.RiskEngineConfig
import com.trustmesh.app.core.incident.SecurityIncidentManager
import com.trustmesh.app.core.protection.ProtectionPolicyEngine
import com.trustmesh.app.data.local.TrustMeshDatabase
import com.trustmesh.app.data.repository.RoomEventRepository
import com.trustmesh.app.interaction.InteractionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OutgoingCallReceiver : BroadcastReceiver() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_NEW_OUTGOING_CALL) {
            val phoneNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: return
            
            Log.i("TrustMeshOutgoing", "Outgoing call detected to $phoneNumber")
            
            scope.launch {
                try {
                    val metadata = mutableMapOf<String, String>()
                    metadata["isIncoming"] = "false"
                    metadata["callerNumber"] = phoneNumber
                    
                    val event = SecurityEvent(
                        type = EventType.OUTGOING_CALL,
                        source = EventSource.CALL_SCREENING_SERVICE,
                        timestamp = System.currentTimeMillis(),
                        identity = phoneNumber,
                        metadata = metadata,
                        initialRisk = RiskLevel.LOW
                    )
                    
                    val db = TrustMeshDatabase.getDatabase(context)
                    val protectionPolicyEngine = ProtectionPolicyEngine(db.protectionPolicyDao(), db.trustedCallerDao())
                    val repo = RoomEventRepository(db)
                    val recentEvents = repo.getRecentEvents(RiskEngineConfig.RELATED_EVENT_WINDOW_MS)

                    val tempInteraction = com.trustmesh.app.interaction.Interaction(
                        id = "screening_${System.currentTimeMillis()}",
                        title = phoneNumber.ifEmpty { "Unknown Caller" },
                        timestamp = "",
                        riskLevel = event.initialRisk,
                        summary = "Outgoing call",
                        evidence = listOf("Outgoing call"),
                        timeline = listOf("Outgoing call initiated")
                    )

                    val riskAssessment = RiskEngine.evaluate(tempInteraction, recentEvents)
                    val activeIncident = SecurityIncidentManager.activeIncident.value
                    
                    // Although we can't easily block an outgoing call from a BroadcastReceiver,
                    // we still evaluate the interaction to record it and set up the context.
                    protectionPolicyEngine.evaluateInteraction(phoneNumber, riskAssessment, activeIncident)
                    
                    InteractionManager.processEvent(event)
                    
                    val callerName = com.trustmesh.app.core.identity.LocalContactIdentityResolver.getContactNameSync(context, phoneNumber) ?: ""
                    withContext(Dispatchers.Main) {
                        try {
                            com.trustmesh.app.ui.screens.protection.ProtectionController.showOutgoingOverlay(
                                context = context,
                                callerName = callerName.ifEmpty { "Unknown Contact" },
                                callerNumber = phoneNumber
                            )
                        } catch (e: Exception) {
                            Log.e("TrustMeshOutgoing", "showOverlay failed for outgoing call", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TrustMeshOutgoing", "Failed to process outgoing call", e)
                }
            }
        }
    }
}
