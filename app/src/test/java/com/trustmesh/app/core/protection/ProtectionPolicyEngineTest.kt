package com.trustmesh.app.core.protection

import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.core.incident.IncidentStatus
import com.trustmesh.app.core.incident.IncidentType
import com.trustmesh.app.core.incident.SecurityIncident
import com.trustmesh.app.core.intelligence.risk.RiskAssessment
import com.trustmesh.app.data.local.dao.ProtectionPolicyDao
import com.trustmesh.app.data.local.dao.TrustedCallerDao
import com.trustmesh.app.data.local.entity.ProtectionPolicyEntity
import com.trustmesh.app.data.local.entity.TrustedCallerEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

// ─── Fakes ─────────────────────────────────────────────────────────────────────

class FakeProtectionPolicyDao(var storedPolicy: ProtectionPolicyEntity? = null) : ProtectionPolicyDao {
    override fun getPolicyFlow(): Flow<ProtectionPolicyEntity?> = flowOf(storedPolicy)
    override suspend fun getPolicySync(): ProtectionPolicyEntity? = storedPolicy
    override suspend fun insertPolicy(policy: ProtectionPolicyEntity) { storedPolicy = policy }
}

class FakeTrustedCallerDao(private val trustedNumbers: Set<String> = emptySet()) : TrustedCallerDao {
    override fun getTrustedCallersFlow(): Flow<List<TrustedCallerEntity>> = flowOf(emptyList())
    override suspend fun getTrustedCallerSync(phoneNumber: String): TrustedCallerEntity? =
        if (phoneNumber in trustedNumbers) TrustedCallerEntity(phoneNumber, "Test", 0L) else null
    override suspend fun insertTrustedCaller(caller: TrustedCallerEntity) {}
    override suspend fun deleteTrustedCaller(phoneNumber: String) {}
}

// ─── Helpers ───────────────────────────────────────────────────────────────────

fun fakeAssessment(level: RiskLevel, score: Int = level.ordinal * 25) =
    RiskAssessment(riskLevel = level, score = score, evidence = listOf("Test evidence"), explanation = "Test", factors = emptyList())

fun fakeIncident(type: IncidentType, severity: RiskLevel) = SecurityIncident(
    incidentId = "inc-1",
    incidentType = type,
    severity = severity,
    status = IncidentStatus.ACTIVE,
    createdAt = 0L,
    updatedAt = 0L,
    resolvedAt = null,
    riskScore = severity.ordinal * 25,
    explanation = "Test incident",
    recommendedActions = listOf("Do not share OTP"),
    relatedInteractionIds = listOf("i1"),
    riskFactors = emptyList(),
    attackContext = null,
    callerIdentity = null,
    callerReputation = null
)

// ─── Tests ─────────────────────────────────────────────────────────────────────

class ProtectionPolicyEngineTest {

    private lateinit var engine: ProtectionPolicyEngine

    private fun createEngine(
        policy: ProtectionPolicyEntity = ProtectionPolicyEntity.default(),
        trustedNumbers: Set<String> = emptySet()
    ): ProtectionPolicyEngine = ProtectionPolicyEngine(
        policyDao = FakeProtectionPolicyDao(policy),
        trustedCallerDao = FakeTrustedCallerDao(trustedNumbers)
    )

    // ── Trusted caller bypass ─────────────────────────────────────────────────

    @Test
    fun `trusted caller bypasses all risk checks`() = runBlocking {
        engine = createEngine(trustedNumbers = setOf("+911234567890"))
        val decision = engine.evaluateInteraction(
            phoneNumber = "+911234567890",
            riskAssessment = fakeAssessment(RiskLevel.CRITICAL),
            activeIncident = fakeIncident(IncidentType.FINANCIAL_FRAUD, RiskLevel.CRITICAL)
        )
        assertEquals(ProtectionAction.MONITOR_ONLY, decision.action)
        assertEquals(RiskLevel.LOW, decision.riskLevel)
        assertTrue(decision.reason.contains("Trusted"))
    }

    // ── Standard mode ─────────────────────────────────────────────────────────

    @Test
    fun `standard mode - low risk returns MONITOR_ONLY`() = runBlocking {
        engine = createEngine()
        val decision = engine.evaluateInteraction(
            "+910000000000",
            fakeAssessment(RiskLevel.LOW),
            null
        )
        assertEquals(ProtectionAction.MONITOR_ONLY, decision.action)
        assertEquals(ProtectionMode.STANDARD, decision.policySource)
    }

    @Test
    fun `standard mode - elevated risk returns SHOW_COMPACT_WARNING`() = runBlocking {
        engine = createEngine()
        val decision = engine.evaluateInteraction("+910000000000", fakeAssessment(RiskLevel.ELEVATED), null)
        assertEquals(ProtectionAction.SHOW_COMPACT_WARNING, decision.action)
    }

    @Test
    fun `standard mode - high risk returns SHOW_RISK_CARD`() = runBlocking {
        engine = createEngine()
        val decision = engine.evaluateInteraction("+910000000000", fakeAssessment(RiskLevel.HIGH), null)
        assertEquals(ProtectionAction.SHOW_RISK_CARD, decision.action)
    }

    @Test
    fun `standard mode - critical risk returns SHOW_SECURITY_INTERVENTION`() = runBlocking {
        engine = createEngine()
        val decision = engine.evaluateInteraction("+910000000000", fakeAssessment(RiskLevel.CRITICAL), null)
        assertEquals(ProtectionAction.SHOW_SECURITY_INTERVENTION, decision.action)
    }

    // ── Strict mode ───────────────────────────────────────────────────────────

    @Test
    fun `strict mode - critical auto-blocks`() = runBlocking {
        val strictPolicy = ProtectionPolicyEntity(
            mode = ProtectionMode.STRICT,
            lowRiskBehavior = ProtectionAction.SHOW_COMPACT_WARNING,
            elevatedRiskBehavior = ProtectionAction.SHOW_RISK_CARD,
            highRiskBehavior = ProtectionAction.SHOW_BOTTOM_SHEET,
            criticalRiskBehavior = ProtectionAction.SHOW_SECURITY_INTERVENTION,
            unknownCallerBehavior = ProtectionAction.SHOW_COMPACT_WARNING,
            autoBlockCritical = true,
            updatedAt = 0L
        )
        engine = createEngine(policy = strictPolicy)
        val decision = engine.evaluateInteraction("+910000000000", fakeAssessment(RiskLevel.CRITICAL), null)
        assertEquals(ProtectionAction.BLOCK_CALL, decision.action)
    }

    @Test
    fun `strict mode - low risk shows compact warning`() = runBlocking {
        val strictPolicy = ProtectionPolicyEntity(
            mode = ProtectionMode.STRICT,
            lowRiskBehavior = ProtectionAction.SHOW_COMPACT_WARNING,
            elevatedRiskBehavior = ProtectionAction.SHOW_RISK_CARD,
            highRiskBehavior = ProtectionAction.SHOW_BOTTOM_SHEET,
            criticalRiskBehavior = ProtectionAction.SHOW_SECURITY_INTERVENTION,
            unknownCallerBehavior = ProtectionAction.SHOW_COMPACT_WARNING,
            autoBlockCritical = true,
            updatedAt = 0L
        )
        engine = createEngine(policy = strictPolicy)
        val decision = engine.evaluateInteraction("+910000000000", fakeAssessment(RiskLevel.LOW), null)
        assertEquals(ProtectionAction.SHOW_COMPACT_WARNING, decision.action)
    }

    // ── Incident overrides ────────────────────────────────────────────────────

    @Test
    fun `active critical incident overrides risk-based decision`() = runBlocking {
        engine = createEngine() // Standard mode
        val incident = fakeIncident(IncidentType.OTP_THEFT, RiskLevel.CRITICAL)
        val decision = engine.evaluateInteraction(
            "+910000000000",
            fakeAssessment(RiskLevel.LOW), // Low raw risk
            incident
        )
        // Incident severity should override LOW risk → SHOW_SECURITY_INTERVENTION
        assertEquals(ProtectionAction.SHOW_SECURITY_INTERVENTION, decision.action)
        assertEquals(IncidentType.OTP_THEFT, decision.incidentType)
    }

    @Test
    fun `active high incident triggers high risk behavior`() = runBlocking {
        engine = createEngine()
        val incident = fakeIncident(IncidentType.FINANCIAL_FRAUD, RiskLevel.HIGH)
        val decision = engine.evaluateInteraction(
            "+910000000000",
            fakeAssessment(RiskLevel.LOW),
            incident
        )
        assertEquals(ProtectionAction.SHOW_RISK_CARD, decision.action)
    }

    @Test
    fun `incident + autoBlock + critical severity blocks call`() = runBlocking {
        val strictPolicy = ProtectionPolicyEntity(
            mode = ProtectionMode.STRICT,
            lowRiskBehavior = ProtectionAction.MONITOR_ONLY,
            elevatedRiskBehavior = ProtectionAction.SHOW_COMPACT_WARNING,
            highRiskBehavior = ProtectionAction.SHOW_RISK_CARD,
            criticalRiskBehavior = ProtectionAction.SHOW_SECURITY_INTERVENTION,
            unknownCallerBehavior = ProtectionAction.MONITOR_ONLY,
            autoBlockCritical = true,
            updatedAt = 0L
        )
        engine = createEngine(policy = strictPolicy)
        val incident = fakeIncident(IncidentType.SOCIAL_ENGINEERING, RiskLevel.CRITICAL)
        val decision = engine.evaluateInteraction("+910000000000", fakeAssessment(RiskLevel.LOW), incident)
        assertEquals(ProtectionAction.BLOCK_CALL, decision.action)
    }

    // ── Standard mode - no auto-block ─────────────────────────────────────────

    @Test
    fun `standard mode does NOT auto-block even critical`() = runBlocking {
        engine = createEngine() // autoBlockCritical = false by default
        val decision = engine.evaluateInteraction("+910000000000", fakeAssessment(RiskLevel.CRITICAL), null)
        assertNotEquals(ProtectionAction.BLOCK_CALL, decision.action)
    }

    // ── Explainability ────────────────────────────────────────────────────────

    @Test
    fun `decision always has a non-empty reason`() = runBlocking {
        engine = createEngine()
        val decision = engine.evaluateInteraction("+910000000000", fakeAssessment(RiskLevel.ELEVATED), null)
        assertTrue(decision.reason.isNotBlank())
    }
}
