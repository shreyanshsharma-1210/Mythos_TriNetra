package com.trustmesh.app.data.local.entity

import androidx.room.Embedded
import androidx.room.Relation

data class InteractionWithDetails(
    @Embedded val interaction: InteractionEntity,
    
    @Relation(
        parentColumn = "id",
        entityColumn = "interactionId"
    )
    val riskAssessment: RiskAssessmentEntity?,
    
    @Relation(
        parentColumn = "id",
        entityColumn = "interactionId"
    )
    val riskFactors: List<RiskFactorEntity>,
    
    @Relation(
        parentColumn = "id",
        entityColumn = "interactionId"
    )
    val timelineEntries: List<TimelineEntryEntity>,
    
    @Relation(
        parentColumn = "id",
        entityColumn = "interactionId"
    )
    val evidenceEntries: List<EvidenceEntryEntity>
)
