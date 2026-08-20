package com.trustmesh.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.trustmesh.app.core.intelligence.risk.RiskFactorType

@Entity(
    tableName = "risk_factors",
    foreignKeys = [
        ForeignKey(
            entity = InteractionEntity::class,
            parentColumns = ["id"],
            childColumns = ["interactionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RiskFactorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val interactionId: String,
    val type: RiskFactorType,
    val description: String,
    val source: String,
    val weight: Int
)
