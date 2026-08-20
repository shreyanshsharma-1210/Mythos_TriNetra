package com.trustmesh.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.trustmesh.app.core.events.EventSource
import com.trustmesh.app.core.events.EventType
import com.trustmesh.app.core.events.RiskLevel

@Entity(tableName = "security_events")
data class SecurityEventEntity(
    @PrimaryKey val id: String,
    val type: EventType,
    val source: EventSource,
    val timestamp: Long,
    val interactionId: String?,
    val identity: String?,
    val initialRisk: RiskLevel,
    val metadataString: String // We can serialize Map to JSON string
)
