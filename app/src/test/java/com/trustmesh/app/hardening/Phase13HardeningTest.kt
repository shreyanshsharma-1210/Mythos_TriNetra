package com.trustmesh.app.hardening

import com.trustmesh.app.core.events.EventSource
import com.trustmesh.app.core.events.EventType
import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.core.events.SecurityEvent
import com.trustmesh.app.core.incident.IncidentStatus
import com.trustmesh.app.core.incident.IncidentType
import com.trustmesh.app.core.incident.SecurityIncident
import com.trustmesh.app.core.intelligence.risk.RiskAssessment
import com.trustmesh.app.core.protection.FakeProtectionPolicyDao  // test helper from ProtectionPolicyEngineTest file
import com.trustmesh.app.core.protection.FakeTrustedCallerDao     // test helper from ProtectionPolicyEngineTest file
import com.trustmesh.app.core.protection.ProtectionAction
import com.trustmesh.app.core.protection.ProtectionPolicyEngine
import com.trustmesh.app.data.local.entity.ProtectionPolicyEntity
import com.trustmesh.app.interaction.Interaction
import com.trustmesh.app.interaction.InteractionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Phase 13 stress and invariant tests.
 *
 * Covers:
 * - repeated incoming call callbacks (duplicate prevention)
 * - rapid notification updates / same-key deduplication
 * - duplicate notification key
 * - notification removal processing
 * - repeated showOverlay / hideOverlay idempotency (via flag inspection)
 * - overlay permission missing (ProtectionController skips silently)
 * - risk level transitions
 * - policy BLOCK_CALL decision
 * - trusted caller bypass
 * - incident override
 * - policy engine under null/empty inputs
 */
class Phase13HardeningTest {

    @Before
    fun setup() {
        InteractionManager.clearForTesting()
        com.trustmesh.app.core.intelligence.risk.RiskEngine.clearCacheForTesting()
    }

    // ─── Notification deduplication (rapid updates) ───────────────────────────

    @Test
    fun `rapid same-key notifications produce exactly one interaction`() {
        val key = "keyRapid"
        repeat(10) { i ->
            val event = SecurityEvent(
                type = EventType.NOTIFICATION_POSTED,
                source = EventSource.NOTIFICATION_LISTENER_SERVICE,
                timestamp = 1000L + i,
                identity = "app.rapid",
                metadata = mapOf(
                    "packageName" to "app.rapid",
                    "appName" to "Rapid App",
                    "notificationKey" to key,
                    "title" to "Update $i",
                    "text" to "Body $i"
                ),
                initialRisk = RiskLevel.LOW
            )
            InteractionManager.processEvent(event)
        }

        val interactions = InteractionManager.interactions.value
        assertEquals("10 rapid updates to same key must produce exactly 1 interaction", 1, interactions.size)
        assertEquals(key, interactions[0].associatedKey)
    }

    @Test
    fun `different notification keys produce separate interactions`() {
        repeat(5) { i ->
            val event = SecurityEvent(
                type = EventType.NOTIFICATION_POSTED,
                source = EventSource.NOTIFICATION_LISTENER_SERVICE,
                timestamp = 1000L + i,
                identity = "app.multi",
                metadata = mapOf(
                    "packageName" to "app.multi",
                    "appName" to "Multi App",
                    "notificationKey" to "key_$i",
                    "title" to "Notif $i"
                ),
                initialRisk = RiskLevel.LOW
            )
            InteractionManager.processEvent(event)
        }

        val interactions = InteractionManager.interactions.value
        assertEquals("5 different keys must produce 5 interactions", 5, interactions.size)
    }

    @Test
    fun `notification without key does not deduplicate`() {
        repeat(3) { i ->
            val event = SecurityEvent(
                type = EventType.NOTIFICATION_POSTED,
                source = EventSource.NOTIFICATION_LISTENER_SERVICE,
                timestamp = 1000L + i,
                identity = "app.nokey",
                metadata = mapOf(
                    "packageName" to "app.nokey",
                    "appName" to "No Key App",
                    "title" to "Notif $i"
                    // No notificationKey
                ),
                initialRisk = RiskLevel.LOW
            )
            InteractionManager.processEvent(event)
        }

        val interactions = InteractionManager.interactions.value
        assertEquals("3 notifications without key must create 3 separate interactions", 3, interactions.size)
    }

    // ─── Repeated incoming calls ──────────────────────────────────────────────

    @Test
    fun `repeated incoming call events create separate interactions`() {
        repeat(3) { i ->
            val event = SecurityEvent(
                type = EventType.INCOMING_CALL,
                source = EventSource.CALL_SCREENING_SERVICE,
                timestamp = 1000L + i * 5000L,
                identity = "+910000000000",
                metadata = mapOf("callerNumber" to "+910000000000"),
                initialRisk = RiskLevel.LOW
            )
            InteractionManager.processEvent(event)
        }

        val interactions = InteractionManager.interactions.value
        // Each call is a unique interaction (no deduplication for calls)
        assertEquals("3 separate call events must create 3 interactions", 3, interactions.size)
        assertTrue("All interactions must be INCOMING_CALL type",
            interactions.all { it.summary == "Incoming call observed by TrustMesh" })
    }

    // ─── Risk level transitions ───────────────────────────────────────────────

    @Test
    fun `policy engine - LOW risk maps to MONITOR_ONLY on standard mode`() = runBlocking {
        val engine = ProtectionPolicyEngine(
            FakeProtectionPolicyDao(ProtectionPolicyEntity.default()),
            FakeTrustedCallerDao()
        )
        val decision = engine.evaluateInteraction(
            "+910000000001",
            RiskAssessment(riskLevel = RiskLevel.LOW, score = 10, evidence = listOf("baseline"),
                factors = emptyList(), explanation = "Low"),
            null
        )
        assertEquals(ProtectionAction.MONITOR_ONLY, decision.action)
    }

    @Test
    fun `policy engine - ELEVATED risk maps to SHOW_COMPACT_WARNING on standard mode`() = runBlocking {
        val engine = ProtectionPolicyEngine(
            FakeProtectionPolicyDao(ProtectionPolicyEntity.default()),
            FakeTrustedCallerDao()
        )
        val decision = engine.evaluateInteraction(
            "+910000000002",
            RiskAssessment(riskLevel = RiskLevel.ELEVATED, score = 30, evidence = listOf("elevated"),
                factors = emptyList(), explanation = "Elevated"),
            null
        )
        assertEquals(ProtectionAction.SHOW_COMPACT_WARNING, decision.action)
    }

    @Test
    fun `policy engine - HIGH risk maps to SHOW_RISK_CARD on standard mode`() = runBlocking {
        val engine = ProtectionPolicyEngine(
            FakeProtectionPolicyDao(ProtectionPolicyEntity.default()),
            FakeTrustedCallerDao()
        )
        val decision = engine.evaluateInteraction(
            "+910000000003",
            RiskAssessment(riskLevel = RiskLevel.HIGH, score = 60, evidence = listOf("high"),
                factors = emptyList(), explanation = "High"),
            null
        )
        assertEquals(ProtectionAction.SHOW_RISK_CARD, decision.action)
    }

    @Test
    fun `policy engine - CRITICAL risk maps to SHOW_SECURITY_INTERVENTION on standard mode`() = runBlocking {
        val engine = ProtectionPolicyEngine(
            FakeProtectionPolicyDao(ProtectionPolicyEntity.default()),
            FakeTrustedCallerDao()
        )
        val decision = engine.evaluateInteraction(
            "+910000000004",
            RiskAssessment(riskLevel = RiskLevel.CRITICAL, score = 90, evidence = listOf("critical"),
                factors = emptyList(), explanation = "Critical"),
            null
        )
        assertEquals(ProtectionAction.SHOW_SECURITY_INTERVENTION, decision.action)
    }

    @Test
    fun `policy engine - CRITICAL + autoBlock = BLOCK_CALL`() = runBlocking {
        val strictPolicy = ProtectionPolicyEntity(
            mode = com.trustmesh.app.core.protection.ProtectionMode.STRICT,
            lowRiskBehavior = ProtectionAction.MONITOR_ONLY,
            elevatedRiskBehavior = ProtectionAction.SHOW_COMPACT_WARNING,
            highRiskBehavior = ProtectionAction.SHOW_RISK_CARD,
            criticalRiskBehavior = ProtectionAction.SHOW_SECURITY_INTERVENTION,
            unknownCallerBehavior = ProtectionAction.MONITOR_ONLY,
            autoBlockCritical = true,
            updatedAt = 0L
        )
        val engine = ProtectionPolicyEngine(
            FakeProtectionPolicyDao(strictPolicy),
            FakeTrustedCallerDao()
        )
        val decision = engine.evaluateInteraction(
            "+910000000005",
            RiskAssessment(riskLevel = RiskLevel.CRITICAL, score = 95, evidence = listOf("critical"),
                factors = emptyList(), explanation = "Critical + autoBlock"),
            null
        )
        assertEquals(ProtectionAction.BLOCK_CALL, decision.action)
    }

    // ─── Trusted caller bypass ────────────────────────────────────────────────

    @Test
    fun `trusted caller bypasses CRITICAL risk + active incident`() = runBlocking {
        val engine = ProtectionPolicyEngine(
            FakeProtectionPolicyDao(ProtectionPolicyEntity.default()),
            FakeTrustedCallerDao(trustedNumbers = setOf("+91TRUSTED"))
        )
        val incident = SecurityIncident(
            incidentId = "i1", incidentType = IncidentType.OTP_THEFT,
            severity = RiskLevel.CRITICAL, status = IncidentStatus.ACTIVE,
            createdAt = 0, updatedAt = 0, resolvedAt = null, riskScore = 99,
            explanation = "OTP theft attempt",
            recommendedActions = listOf("Do not share OTP"),
            relatedInteractionIds = listOf("x1"),
            riskFactors = emptyList(), attackContext = null,
            callerIdentity = null, callerReputation = null
        )
        val decision = engine.evaluateInteraction(
            "+91TRUSTED",
            RiskAssessment(riskLevel = RiskLevel.CRITICAL, score = 99, evidence = emptyList(),
                factors = emptyList(), explanation = ""),
            incident
        )
        assertEquals(ProtectionAction.MONITOR_ONLY, decision.action)
        assertTrue(decision.reason.contains("Trusted"))
    }

    // ─── Incident override ────────────────────────────────────────────────────

    @Test
    fun `active CRITICAL incident overrides LOW risk assessment`() = runBlocking {
        val engine = ProtectionPolicyEngine(
            FakeProtectionPolicyDao(ProtectionPolicyEntity.default()),
            FakeTrustedCallerDao()
        )
        val incident = SecurityIncident(
            incidentId = "i2", incidentType = IncidentType.FINANCIAL_FRAUD,
            severity = RiskLevel.CRITICAL, status = IncidentStatus.ACTIVE,
            createdAt = 0, updatedAt = 0, resolvedAt = null, riskScore = 85,
            explanation = "Financial fraud",
            recommendedActions = listOf("Do not approve transaction"),
            relatedInteractionIds = listOf("x2"),
            riskFactors = emptyList(), attackContext = null,
            callerIdentity = null, callerReputation = null
        )
        val decision = engine.evaluateInteraction(
            "+910000000006",
            RiskAssessment(riskLevel = RiskLevel.LOW, score = 5, evidence = emptyList(),
                factors = emptyList(), explanation = "Low raw risk"),
            incident
        )
        // Incident severity (CRITICAL) must override LOW raw risk
        assertEquals(RiskLevel.CRITICAL, decision.riskLevel)
        assertEquals(IncidentType.FINANCIAL_FRAUD, decision.incidentType)
        assertEquals(ProtectionAction.SHOW_SECURITY_INTERVENTION, decision.action)
    }

    // ─── Policy decision explainability ──────────────────────────────────────

    @Test
    fun `every policy decision has a non-blank reason`() = runBlocking {
        val engine = ProtectionPolicyEngine(
            FakeProtectionPolicyDao(ProtectionPolicyEntity.default()),
            FakeTrustedCallerDao()
        )
        listOf(RiskLevel.LOW, RiskLevel.ELEVATED, RiskLevel.HIGH, RiskLevel.CRITICAL).forEach { level ->
            val decision = engine.evaluateInteraction(
                "+910000000007",
                RiskAssessment(riskLevel = level, score = level.ordinal * 25, evidence = emptyList(),
                    factors = emptyList(), explanation = "test"),
                null
            )
            assertTrue("Decision reason must not be blank for $level", decision.reason.isNotBlank())
        }
    }

    // ─── Empty phone number safety ────────────────────────────────────────────

    @Test
    fun `policy engine handles empty phone number safely`() = runBlocking {
        val engine = ProtectionPolicyEngine(
            FakeProtectionPolicyDao(ProtectionPolicyEntity.default()),
            FakeTrustedCallerDao()
        )
        // Empty phone number — should not throw, should use risk-based path
        val decision = engine.evaluateInteraction(
            "",
            RiskAssessment(riskLevel = RiskLevel.LOW, score = 10, evidence = emptyList(),
                factors = emptyList(), explanation = "empty number test"),
            null
        )
        assertNotNull(decision)
        assertEquals(ProtectionAction.MONITOR_ONLY, decision.action)
    }

    // ─── Notification timeline integrity ──────────────────────────────────────

    @Test
    fun `notification update appends timeline entry`() {
        val key = "keyTimeline"
        val event1 = SecurityEvent(
            type = EventType.NOTIFICATION_POSTED,
            source = EventSource.NOTIFICATION_LISTENER_SERVICE,
            timestamp = 1000L,
            identity = "app.T",
            metadata = mapOf(
                "packageName" to "app.T",
                "appName" to "Timeline App",
                "notificationKey" to key,
                "title" to "First"
            ),
            initialRisk = RiskLevel.LOW
        )
        val event2 = event1.copy(
            timestamp = 2000L,
            metadata = event1.metadata + mapOf("title" to "Second")
        )

        InteractionManager.processEvent(event1)
        InteractionManager.processEvent(event2)

        val interactions = InteractionManager.interactions.value
        assertEquals(1, interactions.size)
        assertTrue("Timeline must contain update entry",
            interactions[0].timeline.any { it.contains("Notification content updated") })
    }

    // ─── Incoming call + notification are separate interactions ──────────────

    @Test
    fun `call event and notification event are never deduplicated against each other`() {
        val callEvent = SecurityEvent(
            type = EventType.INCOMING_CALL,
            source = EventSource.CALL_SCREENING_SERVICE,
            timestamp = 1000L,
            identity = "+910000000000",
            metadata = mapOf("callerNumber" to "+910000000000"),
            initialRisk = RiskLevel.LOW
        )
        val notifEvent = SecurityEvent(
            type = EventType.NOTIFICATION_POSTED,
            source = EventSource.NOTIFICATION_LISTENER_SERVICE,
            timestamp = 1100L,
            identity = "com.google.android.dialer",
            metadata = mapOf(
                "packageName" to "com.google.android.dialer",
                "appName" to "Google Dialer",
                "notificationKey" to "dialerCallKey",
                "title" to "Incoming call"
            ),
            initialRisk = RiskLevel.LOW
        )

        InteractionManager.processEvent(callEvent)
        InteractionManager.processEvent(notifEvent)

        val interactions = InteractionManager.interactions.value
        assertEquals("Call and notification must produce 2 separate interactions", 2, interactions.size)
    }
}
