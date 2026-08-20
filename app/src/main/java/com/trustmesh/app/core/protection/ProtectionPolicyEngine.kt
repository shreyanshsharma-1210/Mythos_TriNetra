package com.trustmesh.app.core.protection

import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.core.incident.IncidentType
import com.trustmesh.app.core.incident.SecurityIncident
import com.trustmesh.app.core.intelligence.risk.RiskAssessment
import com.trustmesh.app.data.local.dao.ProtectionPolicyDao
import com.trustmesh.app.data.local.dao.TrustedCallerDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProtectionPolicyEngine(
    private val policyDao: ProtectionPolicyDao,
    private val trustedCallerDao: TrustedCallerDao
) {

    /**
     * Evaluates the appropriate ProtectionDecision for a given interaction.
     * Takes into account the current active incident, the interaction's risk assessment,
     * whether the caller is trusted, and the user's protection policy settings.
     */
    suspend fun evaluateInteraction(
        phoneNumber: String,
        riskAssessment: RiskAssessment,
        activeIncident: SecurityIncident?
    ): ProtectionDecision {
        val policy = policyDao.getPolicySync() ?: com.trustmesh.app.data.local.entity.ProtectionPolicyEntity.default()
        
        // 1. Check if caller is trusted
        val isTrusted = trustedCallerDao.getTrustedCallerSync(phoneNumber) != null
        if (isTrusted) {
            return ProtectionDecision(
                action = ProtectionAction.MONITOR_ONLY,
                reason = "Caller is in Trusted List",
                riskLevel = RiskLevel.LOW,
                riskScore = 0,
                incidentType = null,
                requiresUserConfirmation = false,
                policySource = policy.mode
            )
        }

        // 2. Incident-based decision
        if (activeIncident != null) {
            // Incidents are typically HIGH or CRITICAL risk by nature
            val action = when (activeIncident.severity) {
                RiskLevel.CRITICAL -> policy.criticalRiskBehavior
                RiskLevel.HIGH -> policy.highRiskBehavior
                RiskLevel.ELEVATED -> policy.elevatedRiskBehavior
                else -> policy.lowRiskBehavior
            }
            
            // Override for autoBlockCritical
            val finalAction = if (policy.autoBlockCritical && activeIncident.severity == RiskLevel.CRITICAL) {
                ProtectionAction.BLOCK_CALL
            } else {
                action
            }

            return ProtectionDecision(
                action = finalAction,
                reason = "Active Security Incident: ${activeIncident.incidentType.name}",
                riskLevel = activeIncident.severity,
                riskScore = activeIncident.riskScore,
                incidentType = activeIncident.incidentType,
                requiresUserConfirmation = finalAction == ProtectionAction.ASK_USER,
                policySource = policy.mode
            )
        }

        // 3. Risk-based decision
        val action = when (riskAssessment.riskLevel) {
            RiskLevel.CRITICAL -> policy.criticalRiskBehavior
            RiskLevel.HIGH -> policy.highRiskBehavior
            RiskLevel.ELEVATED -> policy.elevatedRiskBehavior
            else -> policy.lowRiskBehavior
        }
        
        val finalAction = if (policy.autoBlockCritical && riskAssessment.riskLevel == RiskLevel.CRITICAL) {
            ProtectionAction.BLOCK_CALL
        } else {
            action
        }

        return ProtectionDecision(
            action = finalAction,
            reason = "Risk Level: ${riskAssessment.riskLevel.name}",
            riskLevel = riskAssessment.riskLevel,
            riskScore = riskAssessment.score,
            incidentType = null,
            requiresUserConfirmation = finalAction == ProtectionAction.ASK_USER,
            policySource = policy.mode
        )
    }
}
