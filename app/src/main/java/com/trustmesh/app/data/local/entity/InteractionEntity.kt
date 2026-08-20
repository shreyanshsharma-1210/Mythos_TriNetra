package com.trustmesh.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.core.identity.IdentitySource
import com.trustmesh.app.core.identity.IdentityType

@Entity(tableName = "interactions")
data class InteractionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val timestamp: String,
    val timestampMs: Long,
    val riskLevel: RiskLevel,
    val summary: String,
    val associatedKey: String?,
    val appName: String?,
    val notificationTitle: String?,
    val notificationText: String?,
    val packageName: String?,
    
    // Caller Identity
    val callerPhoneNumber: String?,
    val callerIdentityName: String?,
    val callerIdentityType: IdentityType?,
    val callerIdentitySource: IdentitySource?,
    val callerIdentityConfidence: com.trustmesh.app.core.identity.Confidence?,
    val callerIdentityIsKnown: Boolean?,
    
    // Caller Reputation
    val repCategory: com.trustmesh.app.core.identity.CallerCategory?,
    val repLevel: com.trustmesh.app.core.identity.ReputationLevel?,
    val repSpamReports: Int?,
    val repFraudReports: Int?
)
