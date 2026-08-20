package com.trustmesh.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.trustmesh.app.core.events.RiskLevel

@Entity(
    tableName = "risk_assessments",
    foreignKeys = [
        ForeignKey(
            entity = InteractionEntity::class,
            parentColumns = ["id"],
            childColumns = ["interactionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RiskAssessmentEntity(
    @PrimaryKey val interactionId: String,
    val score: Int,
    val riskLevel: RiskLevel,
    val explanation: String
)
