package com.trustmesh.app.core.protection

import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.core.incident.IncidentType

data class ProtectionDecision(
    val action: ProtectionAction,
    val reason: String,
    val riskLevel: RiskLevel,
    val riskScore: Int,
    val incidentType: IncidentType?,
    val requiresUserConfirmation: Boolean,
    val policySource: ProtectionMode
)
