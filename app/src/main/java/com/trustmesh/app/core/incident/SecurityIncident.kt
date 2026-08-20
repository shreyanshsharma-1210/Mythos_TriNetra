package com.trustmesh.app.core.incident

import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.core.identity.CallerIdentity
import com.trustmesh.app.core.identity.CallerReputation
import com.trustmesh.app.core.intelligence.context.AttackContext
import com.trustmesh.app.core.intelligence.risk.RiskFactor

data class SecurityIncident(
    val incidentId: String,
    val incidentType: IncidentType,
    val severity: RiskLevel,
    val status: IncidentStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val resolvedAt: Long? = null,
    val riskScore: Int,
    val explanation: String,
    val relatedInteractionIds: List<String>,
    val riskFactors: List<RiskFactor>,
    val attackContext: AttackContext?,
    val callerIdentity: CallerIdentity?,
    val callerReputation: CallerReputation?,
    val recommendedActions: List<String>
)
