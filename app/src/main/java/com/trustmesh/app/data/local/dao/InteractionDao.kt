package com.trustmesh.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.trustmesh.app.data.local.entity.EvidenceEntryEntity
import com.trustmesh.app.data.local.entity.InteractionEntity
import com.trustmesh.app.data.local.entity.InteractionWithDetails
import com.trustmesh.app.data.local.entity.RiskAssessmentEntity
import com.trustmesh.app.data.local.entity.RiskFactorEntity
import com.trustmesh.app.data.local.entity.TimelineEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InteractionDao {

    @Transaction
    @Query("SELECT * FROM interactions ORDER BY timestampMs DESC")
    fun observeAllInteractions(): Flow<List<InteractionWithDetails>>

    @Transaction
    @Query("SELECT * FROM interactions WHERE id = :id")
    suspend fun getInteraction(id: String): InteractionWithDetails?

    @Transaction
    @Query("SELECT * FROM interactions ORDER BY timestampMs DESC")
    suspend fun getAllInteractionsSync(): List<InteractionWithDetails>
    
    @Query("SELECT * FROM interactions WHERE associatedKey = :key LIMIT 1")
    suspend fun getInteractionByAssociatedKey(key: String): InteractionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInteraction(interaction: InteractionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRiskAssessment(assessment: RiskAssessmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRiskFactors(factors: List<RiskFactorEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimelineEntries(entries: List<TimelineEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvidenceEntries(entries: List<EvidenceEntryEntity>)

    @Query("DELETE FROM interactions WHERE id = :id")
    suspend fun deleteInteraction(id: String)
    
    @Query("DELETE FROM interactions")
    suspend fun clearAll()

    @Query("DELETE FROM risk_assessments WHERE interactionId = :interactionId")
    suspend fun deleteRiskAssessment(interactionId: String)

    @Query("DELETE FROM risk_factors WHERE interactionId = :interactionId")
    suspend fun deleteRiskFactors(interactionId: String)

    @Query("DELETE FROM timeline_entries WHERE interactionId = :interactionId")
    suspend fun deleteTimelineEntries(interactionId: String)

    @Query("DELETE FROM evidence_entries WHERE interactionId = :interactionId")
    suspend fun deleteEvidenceEntries(interactionId: String)

    @Transaction
    suspend fun saveInteractionWithDetails(
        interaction: InteractionEntity,
        riskAssessment: RiskAssessmentEntity?,
        riskFactors: List<RiskFactorEntity>,
        timelineEntries: List<TimelineEntryEntity>,
        evidenceEntries: List<EvidenceEntryEntity>
    ) {
        insertInteraction(interaction)
        
        deleteRiskAssessment(interaction.id)
        if (riskAssessment != null) {
            insertRiskAssessment(riskAssessment)
        }
        
        deleteRiskFactors(interaction.id)
        if (riskFactors.isNotEmpty()) {
            insertRiskFactors(riskFactors)
        }
        
        deleteTimelineEntries(interaction.id)
        if (timelineEntries.isNotEmpty()) {
            insertTimelineEntries(timelineEntries)
        }
        
        deleteEvidenceEntries(interaction.id)
        if (evidenceEntries.isNotEmpty()) {
            insertEvidenceEntries(evidenceEntries)
        }
    }
}
