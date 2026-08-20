package com.trustmesh.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.core.incident.IncidentStatus
import com.trustmesh.app.core.incident.IncidentType

@Entity(tableName = "security_incidents")
data class SecurityIncidentEntity(
    @PrimaryKey val incidentId: String,
    val incidentType: IncidentType,
    val severity: RiskLevel,
    val status: IncidentStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val resolvedAt: Long?,
    val riskScore: Int,
    val explanation: String,
    val recommendedActions: String, // Stored as JSON or comma-separated
    val relatedInteractionIds: String, // Comma-separated
    // Caller identity snapshot
    val callerPhoneNumber: String?,
    val callerDisplayName: String?,
    val callerIdentityType: com.trustmesh.app.core.identity.IdentityType?,
    val callerIdentityConfidence: com.trustmesh.app.core.identity.Confidence?,
    val callerIdentitySource: com.trustmesh.app.core.identity.IdentitySource?,
    // Reputation snapshot
    val repCategory: com.trustmesh.app.core.identity.CallerCategory?,
    val repLevel: com.trustmesh.app.core.identity.ReputationLevel?,
    val repSpamReports: Int?,
    val repFraudReports: Int?
)
