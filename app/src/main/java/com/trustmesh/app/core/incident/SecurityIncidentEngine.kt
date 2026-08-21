package com.trustmesh.app.core.incident

import com.trustmesh.app.core.intelligence.context.ContextType
import com.trustmesh.app.interaction.Interaction
import java.util.UUID

object SecurityIncidentEngine {

    fun determineIncidentType(interaction: Interaction): IncidentType? {
        val attackContext = interaction.riskAssessment?.attackContext ?: return null
        
        return when (attackContext.contextType) {
            ContextType.OTP_THEFT -> IncidentType.OTP_THEFT
            ContextType.FINANCIAL_FRAUD -> IncidentType.FINANCIAL_FRAUD
            ContextType.SOCIAL_ENGINEERING -> IncidentType.SOCIAL_ENGINEERING
            ContextType.SUSPICIOUS_CALL_SEQUENCE -> IncidentType.SUSPICIOUS_CALL
            else -> IncidentType.OTHER
        }
    }

    fun belongsToIncident(interaction: Interaction, incident: SecurityIncident): Boolean {
        // If they share the same incident type and fall within a reasonable time window (e.g. 15 minutes)
        if (incident.status != IncidentStatus.ACTIVE) return false
        val newType = determineIncidentType(interaction)
        if (newType == null || newType != incident.incidentType) return false
        
        val timeDiff = Math.abs(interaction.timestampMs - incident.updatedAt)
        return timeDiff < 15 * 60 * 1000 // 15 minutes
    }

    fun createIncident(interaction: Interaction): SecurityIncident? {
        val incidentType = determineIncidentType(interaction) ?: return null
        val riskAssessment = interaction.riskAssessment ?: return null
        
        val recommendedActions = mutableListOf<String>()
        when (incidentType) {
            IncidentType.OTP_THEFT -> {
                recommendedActions.add("Do not share OTP")
                recommendedActions.add("Verify caller identity")
                recommendedActions.add("End suspicious call")
            }
            IncidentType.FINANCIAL_FRAUD -> {
                recommendedActions.add("Do not approve transactions")
                recommendedActions.add("Verify with your bank")
            }
            IncidentType.SOCIAL_ENGINEERING -> {
                recommendedActions.add("Do not share personal info")
                recommendedActions.add("Verify caller identity")
            }
            IncidentType.SPAM_SCAM -> {
                recommendedActions.add("Do not engage")
                recommendedActions.add("Block number")
            }
            IncidentType.SUSPICIOUS_CALL, IncidentType.OTHER -> {
                recommendedActions.add("Proceed with caution")
            }
        }
        
        return SecurityIncident(
            incidentId = UUID.randomUUID().toString(),
            incidentType = incidentType,
            severity = riskAssessment.riskLevel,
            status = IncidentStatus.ACTIVE,
            createdAt = interaction.timestampMs,
            updatedAt = interaction.timestampMs,
            riskScore = riskAssessment.score,
            explanation = riskAssessment.explanation,
            relatedInteractionIds = listOf(interaction.id),
            riskFactors = riskAssessment.factors,
            attackContext = riskAssessment.attackContext,
            callerIdentity = interaction.callerIdentity,
            callerReputation = interaction.callerReputation,
            recommendedActions = recommendedActions
        )
    }

    fun updateIncident(incident: SecurityIncident, interaction: Interaction): SecurityIncident {
        val riskAssessment = interaction.riskAssessment ?: return incident
        
        val newScore = Math.max(incident.riskScore, riskAssessment.score)
        val newSeverity = if (riskAssessment.riskLevel.ordinal > incident.severity.ordinal) riskAssessment.riskLevel else incident.severity
        val newRelated = (incident.relatedInteractionIds + interaction.id).distinct()
        
        return incident.copy(
            updatedAt = interaction.timestampMs,
            riskScore = newScore,
            severity = newSeverity,
            relatedInteractionIds = newRelated
        )
    }
}
