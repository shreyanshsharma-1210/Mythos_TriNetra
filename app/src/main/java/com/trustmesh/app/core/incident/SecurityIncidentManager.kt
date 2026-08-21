package com.trustmesh.app.core.incident

import android.content.Context
import com.trustmesh.app.data.local.TrustMeshDatabase
import com.trustmesh.app.data.local.dao.SecurityIncidentDao
import com.trustmesh.app.data.local.entity.SecurityIncidentEntity
import com.trustmesh.app.interaction.Interaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object SecurityIncidentManager {
    private val _incidents = MutableStateFlow<List<SecurityIncident>>(emptyList())
    val incidents: StateFlow<List<SecurityIncident>> = _incidents.asStateFlow()

    private val _activeIncident = MutableStateFlow<SecurityIncident?>(null)
    /** The most recent ACTIVE incident, or null if none exists. */
    val activeIncident: StateFlow<SecurityIncident?> = _activeIncident.asStateFlow()

    private var dao: SecurityIncidentDao? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun init(context: Context) {
        if (dao == null) {
            val db = TrustMeshDatabase.getDatabase(context.applicationContext)
            dao = db.securityIncidentDao()
            
            scope.launch {
                dao?.getAllIncidentsFlow()?.collect { entities ->
                    _incidents.value = entities.map { it.toDomain() }
                    _activeIncident.value = _incidents.value.firstOrNull { it.status == IncidentStatus.ACTIVE }
                }
            }
        }
    }

    fun processInteraction(interaction: Interaction) {
        scope.launch {
            val currentIncidents = _incidents.value
            
            var existingIncident: SecurityIncident? = null
            for (inc in currentIncidents) {
                if (inc.status == IncidentStatus.ACTIVE && inc.relatedInteractionIds.contains(interaction.id)) {
                    existingIncident = inc
                    break
                } else if (SecurityIncidentEngine.belongsToIncident(interaction, inc)) {
                    existingIncident = inc
                    break
                }
            }

            if (existingIncident != null) {
                val newType = SecurityIncidentEngine.determineIncidentType(interaction)
                if (newType == null && existingIncident.relatedInteractionIds.size == 1 && existingIncident.relatedInteractionIds.contains(interaction.id)) {
                    // False positive incident — identity likely resolved to a known contact
                    dismissIncident(existingIncident.incidentId)
                } else {
                    val updated = SecurityIncidentEngine.updateIncident(existingIncident, interaction)
                    dao?.updateIncident(updated.toEntity())
                }
            } else {
                val newIncident = SecurityIncidentEngine.createIncident(interaction)
                if (newIncident != null) {
                    dao?.insertIncident(newIncident.toEntity())
                }
            }
        }
    }
    
    fun resolveIncident(incidentId: String) {
        scope.launch {
            val incident = _incidents.value.find { it.incidentId == incidentId }
            if (incident != null) {
                val updated = incident.copy(
                    status = IncidentStatus.RESOLVED,
                    resolvedAt = System.currentTimeMillis()
                )
                dao?.updateIncident(updated.toEntity())
            }
        }
    }
    
    fun dismissIncident(incidentId: String) {
        scope.launch {
            val incident = _incidents.value.find { it.incidentId == incidentId }
            if (incident != null) {
                val updated = incident.copy(
                    status = IncidentStatus.DISMISSED,
                    resolvedAt = System.currentTimeMillis()
                )
                dao?.updateIncident(updated.toEntity())
            }
        }
    }

    private fun SecurityIncidentEntity.toDomain(): SecurityIncident {
        val callerIdentity = if (callerPhoneNumber != null) {
            com.trustmesh.app.core.identity.CallerIdentity(
                phoneNumber = callerPhoneNumber,
                displayName = callerDisplayName,
                identityType = callerIdentityType ?: com.trustmesh.app.core.identity.IdentityType.UNKNOWN,
                confidence = callerIdentityConfidence ?: com.trustmesh.app.core.identity.Confidence.LOW,
                source = callerIdentitySource ?: com.trustmesh.app.core.identity.IdentitySource.UNKNOWN
            )
        } else null

        val callerReputation = if (repCategory != null) {
            com.trustmesh.app.core.identity.CallerReputation(
                phoneNumber = callerPhoneNumber ?: "",
                displayName = callerDisplayName,
                category = repCategory,
                reputationLevel = repLevel ?: com.trustmesh.app.core.identity.ReputationLevel.UNKNOWN,
                confidence = callerIdentityConfidence ?: com.trustmesh.app.core.identity.Confidence.LOW,
                spamReports = repSpamReports ?: 0,
                fraudReports = repFraudReports ?: 0
            )
        } else null

        return SecurityIncident(
            incidentId = incidentId,
            incidentType = incidentType,
            severity = severity,
            status = status,
            createdAt = createdAt,
            updatedAt = updatedAt,
            resolvedAt = resolvedAt,
            riskScore = riskScore,
            explanation = explanation,
            recommendedActions = recommendedActions.split(",").filter { it.isNotEmpty() },
            relatedInteractionIds = relatedInteractionIds.split(",").filter { it.isNotEmpty() },
            riskFactors = emptyList(), // Not currently persisting full risk factors list in entity, could use JSON if needed
            attackContext = null, // Not currently persisting full attack context, could use JSON if needed
            callerIdentity = callerIdentity,
            callerReputation = callerReputation
        )
    }

    private fun SecurityIncident.toEntity(): SecurityIncidentEntity {
        return SecurityIncidentEntity(
            incidentId = incidentId,
            incidentType = incidentType,
            severity = severity,
            status = status,
            createdAt = createdAt,
            updatedAt = updatedAt,
            resolvedAt = resolvedAt,
            riskScore = riskScore,
            explanation = explanation,
            recommendedActions = recommendedActions.joinToString(","),
            relatedInteractionIds = relatedInteractionIds.joinToString(","),
            callerPhoneNumber = callerIdentity?.phoneNumber,
            callerDisplayName = callerIdentity?.displayName,
            callerIdentityType = callerIdentity?.identityType,
            callerIdentityConfidence = callerIdentity?.confidence,
            callerIdentitySource = callerIdentity?.source,
            repCategory = callerReputation?.category,
            repLevel = callerReputation?.reputationLevel,
            repSpamReports = callerReputation?.spamReports,
            repFraudReports = callerReputation?.fraudReports
        )
    }
}
