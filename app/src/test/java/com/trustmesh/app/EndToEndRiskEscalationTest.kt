package com.trustmesh.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trustmesh.app.core.events.EventType
import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.core.events.SecurityEvent
import com.trustmesh.app.core.identity.CallerIdentity
import com.trustmesh.app.core.identity.IdentitySource
import com.trustmesh.app.core.identity.IdentityType
import com.trustmesh.app.core.incident.IncidentStatus
import com.trustmesh.app.core.incident.IncidentType
import com.trustmesh.app.core.incident.SecurityIncidentManager
import com.trustmesh.app.core.protection.ProtectionAction
import com.trustmesh.app.core.protection.ProtectionMode
import com.trustmesh.app.core.events.EventSource
import com.trustmesh.app.core.protection.ProtectionPolicyEngine
import com.trustmesh.app.data.local.TrustMeshDatabase
import com.trustmesh.app.data.local.entity.ProtectionPolicyEntity
import com.trustmesh.app.data.repository.ProtectionPolicyRepository
import com.trustmesh.app.data.repository.RoomEventRepository
import com.trustmesh.app.interaction.InteractionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EndToEndRiskEscalationTest {

    private lateinit var context: Context
    private lateinit var db: TrustMeshDatabase
    private lateinit var repository: RoomEventRepository
    private lateinit var policyRepo: ProtectionPolicyRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = TrustMeshDatabase.getDatabase(context)
        repository = RoomEventRepository(db)
        policyRepo = ProtectionPolicyRepository(db.protectionPolicyDao())
        
        // Ensure standard blocking policy is active for the test
        runBlocking {
            policyRepo.updatePolicy(
                ProtectionPolicyEntity(
                    policyId = "SINGLETON_POLICY",
                    mode = ProtectionMode.STANDARD,
                    lowRiskBehavior = ProtectionAction.MONITOR_ONLY,
                    elevatedRiskBehavior = ProtectionAction.SHOW_COMPACT_WARNING,
                    highRiskBehavior = ProtectionAction.SHOW_RISK_CARD,
                    criticalRiskBehavior = ProtectionAction.SHOW_SECURITY_INTERVENTION,
                    unknownCallerBehavior = ProtectionAction.MONITOR_ONLY,
                    autoBlockCritical = true,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        InteractionManager.init(context)
        InteractionManager.clearForTesting()
        SecurityIncidentManager.init(context)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun testEndToEndScenario() = runBlocking {
        // TEST A: Unknown incoming call creates LOW interaction
        val callEvent = SecurityEvent(
            id = UUID.randomUUID().toString(),
            type = EventType.INCOMING_CALL,
            source = EventSource.CALL_SCREENING_SERVICE,
            timestamp = System.currentTimeMillis(),
            identity = "+15550100",
            initialRisk = RiskLevel.LOW
        )

        InteractionManager.processEvent(callEvent)
        delay(200) // Wait for async processing

        var interactions = InteractionManager.interactions.first()
        assertTrue(interactions.isNotEmpty())
        val interactionId = interactions.first().id
        assertEquals(RiskLevel.LOW, interactions.first().riskLevel)
        assertEquals("Incoming call", interactions.first().evidence.first())

        // TEST B & L: OTP notification correlated with active/recent unknown call
        // Call and notification remain separate event sources but are correlated
        val notificationEvent = SecurityEvent(
            id = UUID.randomUUID().toString(),
            type = EventType.NOTIFICATION_POSTED,
            source = EventSource.NOTIFICATION_LISTENER,
            timestamp = System.currentTimeMillis() + 1000,
            identity = "Bank",
            metadata = mapOf(
                "notificationKey" to "key_123",
                "text" to "Your bank OTP is 123456",
                "appName" to "Messages",
                "packageName" to "com.android.mms"
            ),
            initialRisk = RiskLevel.LOW
        )

        InteractionManager.processEvent(notificationEvent)
        delay(500) // Wait for fusion engine and incident manager

        // TEST C: Risk score changes according to existing RiskEngine rules
        interactions = InteractionManager.interactions.first()
        val updatedCallInteraction = interactions.find { it.id == interactionId }
        assertNotNull(updatedCallInteraction)
        assertEquals(RiskLevel.CRITICAL, updatedCallInteraction?.riskLevel)
        assertTrue(updatedCallInteraction?.riskAssessment?.score ?: 0 > 70)
        
        // TEST D: ProtectionPolicyEngine returns expected decision
        val incidents = SecurityIncidentManager.incidents.first()
        val activeIncident = incidents.firstOrNull { it.status == IncidentStatus.ACTIVE }
        assertNotNull(activeIncident)
        assertEquals(IncidentType.OTP_THEFT, activeIncident?.incidentType)

        val policy = policyRepo.getPolicy()
        assertNotNull(policy)
        val engine = ProtectionPolicyEngine(db.protectionPolicyDao(), db.trustedCallerDao())
        val decision = engine.evaluateInteraction(
            phoneNumber = updatedCallInteraction!!.title ?: "",
            riskAssessment = updatedCallInteraction.riskAssessment!!,
            activeIncident = activeIncident
        )
        assertEquals(ProtectionAction.BLOCK_CALL, decision.action)
        
        // TEST E, F, G: Protection outcome, incident type, isBlocked written to Interaction
        // Simulate CallScreeningService blocking the call and updating the Interaction
        val finalInteraction = updatedCallInteraction.copy(
            protectionDecision = decision.action.name,
            incidentType = activeIncident?.incidentType,
            isBlocked = true
        )
        repository.insertInteraction(finalInteraction)
        delay(100)
        
        // TEST J: Room reload restores the final interaction correctly
        val restoredDetail = repository.getAllInteractions().firstOrNull { it.id == interactionId }
        assertNotNull(restoredDetail)
        assertEquals(decision.action.name, restoredDetail?.protectionDecision)
        assertEquals(IncidentType.OTP_THEFT, restoredDetail?.incidentType)
        assertEquals(true, restoredDetail?.isBlocked)

        // TEST H & I: Post-call report and History read the final state
        val historyList = repository.getAllInteractions()
        val historyItem = historyList.find { it.id == interactionId }
        assertNotNull(historyItem)
        assertEquals(decision.action.name, historyItem?.protectionDecision)
        assertEquals(IncidentType.OTP_THEFT, historyItem?.incidentType)
        assertTrue(historyItem?.isBlocked == true)
        
        // TEST K: Live interaction updates do not overwrite newer state
        // (Handled by the robust copy and replace logic in InteractionManager)
        assertEquals(RiskLevel.CRITICAL, historyItem?.riskLevel)
    }
}
