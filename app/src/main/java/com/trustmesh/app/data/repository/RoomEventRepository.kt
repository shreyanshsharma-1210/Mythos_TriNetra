package com.trustmesh.app.data.repository

import com.trustmesh.app.core.events.EventRepository
import com.trustmesh.app.core.events.SecurityEvent
import com.trustmesh.app.core.identity.CallerIdentity
import com.trustmesh.app.core.intelligence.risk.RiskAssessment
import com.trustmesh.app.core.intelligence.risk.RiskFactor
import com.trustmesh.app.data.local.TrustMeshDatabase
import com.trustmesh.app.data.local.entity.EvidenceEntryEntity
import com.trustmesh.app.data.local.entity.InteractionEntity
import com.trustmesh.app.data.local.entity.RiskAssessmentEntity
import com.trustmesh.app.data.local.entity.RiskFactorEntity
import com.trustmesh.app.data.local.entity.SecurityEventEntity
import com.trustmesh.app.data.local.entity.TimelineEntryEntity
import com.trustmesh.app.interaction.Interaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RoomEventRepository(private val database: TrustMeshDatabase) : EventRepository {

    override fun observeInteractions(): Flow<List<Interaction>> {
        return database.interactionDao().observeAllInteractions().map { list ->
            list.map { detail ->
                
                val callerIdentity = if (detail.interaction.callerPhoneNumber != null) {
                    CallerIdentity(
                        phoneNumber = detail.interaction.callerPhoneNumber,
                        displayName = detail.interaction.callerIdentityName,
                        identityType = detail.interaction.callerIdentityType ?: com.trustmesh.app.core.identity.IdentityType.UNKNOWN,
                        source = detail.interaction.callerIdentitySource ?: com.trustmesh.app.core.identity.IdentitySource.UNKNOWN,
                        confidence = detail.interaction.callerIdentityConfidence ?: com.trustmesh.app.core.identity.Confidence.NONE,
                        isKnown = detail.interaction.callerIdentityIsKnown ?: false
                    )
                } else null
                
                val callerReputation = if (detail.interaction.repLevel != null && detail.interaction.repCategory != null) {
                    com.trustmesh.app.core.identity.CallerReputation(
                        phoneNumber = detail.interaction.callerPhoneNumber ?: "",
                        category = detail.interaction.repCategory,
                        reputationLevel = detail.interaction.repLevel,
                        spamReports = detail.interaction.repSpamReports ?: 0,
                        fraudReports = detail.interaction.repFraudReports ?: 0
                    )
                } else null

                val riskAssessment = detail.riskAssessment?.let { ra ->
                    RiskAssessment(
                        score = ra.score,
                        riskLevel = ra.riskLevel,
                        explanation = ra.explanation,
                        factors = detail.riskFactors.map { rf ->
                            RiskFactor(
                                type = rf.type,
                                description = rf.description,
                                source = rf.source,
                                weight = rf.weight
                            )
                        },
                        evidence = emptyList()
                    )
                }

                Interaction(
                    id = detail.interaction.id,
                    title = detail.interaction.title,
                    timestamp = detail.interaction.timestamp,
                    timestampMs = detail.interaction.timestampMs,
                    riskLevel = detail.interaction.riskLevel,
                    summary = detail.interaction.summary,
                    associatedKey = detail.interaction.associatedKey,
                    appName = detail.interaction.appName,
                    notificationTitle = detail.interaction.notificationTitle,
                    notificationText = detail.interaction.notificationText,
                    packageName = detail.interaction.packageName,
                    callerIdentity = callerIdentity,
                    callerReputation = callerReputation,
                    riskAssessment = riskAssessment,
                    evidence = detail.evidenceEntries.sortedBy { it.orderIndex }.map { it.content },
                    timeline = detail.timelineEntries.sortedBy { it.orderIndex }.map { it.content },
                    protectionDecision = detail.interaction.protectionDecision,
                    incidentType = detail.interaction.incidentType,
                    isBlocked = detail.interaction.isBlocked
                )
            }
        }
    }

    override suspend fun getAllInteractions(): List<Interaction> {
        return withContext(Dispatchers.IO) {
            val list = database.interactionDao().getAllInteractionsSync()
            list.map { detail ->
                val callerIdentity = if (detail.interaction.callerPhoneNumber != null) {
                    CallerIdentity(
                        phoneNumber = detail.interaction.callerPhoneNumber,
                        displayName = detail.interaction.callerIdentityName,
                        identityType = detail.interaction.callerIdentityType ?: com.trustmesh.app.core.identity.IdentityType.UNKNOWN,
                        source = detail.interaction.callerIdentitySource ?: com.trustmesh.app.core.identity.IdentitySource.UNKNOWN,
                        confidence = detail.interaction.callerIdentityConfidence ?: com.trustmesh.app.core.identity.Confidence.NONE,
                        isKnown = detail.interaction.callerIdentityIsKnown ?: false
                    )
                } else null
                
                val callerReputation = if (detail.interaction.repLevel != null && detail.interaction.repCategory != null) {
                    com.trustmesh.app.core.identity.CallerReputation(
                        phoneNumber = detail.interaction.callerPhoneNumber ?: "",
                        category = detail.interaction.repCategory,
                        reputationLevel = detail.interaction.repLevel,
                        spamReports = detail.interaction.repSpamReports ?: 0,
                        fraudReports = detail.interaction.repFraudReports ?: 0
                    )
                } else null

                val riskAssessment = detail.riskAssessment?.let { ra ->
                    RiskAssessment(
                        score = ra.score,
                        riskLevel = ra.riskLevel,
                        explanation = ra.explanation,
                        factors = detail.riskFactors.map { rf ->
                            RiskFactor(
                                type = rf.type,
                                description = rf.description,
                                source = rf.source,
                                weight = rf.weight
                            )
                        },
                        evidence = emptyList()
                    )
                }

                Interaction(
                    id = detail.interaction.id,
                    title = detail.interaction.title,
                    timestamp = detail.interaction.timestamp,
                    timestampMs = detail.interaction.timestampMs,
                    riskLevel = detail.interaction.riskLevel,
                    summary = detail.interaction.summary,
                    associatedKey = detail.interaction.associatedKey,
                    appName = detail.interaction.appName,
                    notificationTitle = detail.interaction.notificationTitle,
                    notificationText = detail.interaction.notificationText,
                    packageName = detail.interaction.packageName,
                    callerIdentity = callerIdentity,
                    callerReputation = callerReputation,
                    riskAssessment = riskAssessment,
                    evidence = detail.evidenceEntries.sortedBy { it.orderIndex }.map { it.content },
                    timeline = detail.timelineEntries.sortedBy { it.orderIndex }.map { it.content },
                    protectionDecision = detail.interaction.protectionDecision,
                    incidentType = detail.interaction.incidentType,
                    isBlocked = detail.interaction.isBlocked
                )
            }
        }
    }

    override suspend fun insertInteraction(interaction: Interaction) {
        saveInteraction(interaction)
    }

    override suspend fun updateInteraction(interaction: Interaction) {
        saveInteraction(interaction)
    }

    private suspend fun saveInteraction(interaction: Interaction) {
        withContext(Dispatchers.IO) {
            val entity = InteractionEntity(
                id = interaction.id,
                title = interaction.title,
                timestamp = interaction.timestamp,
                timestampMs = interaction.timestampMs,
                riskLevel = interaction.riskLevel,
                summary = interaction.summary,
                associatedKey = interaction.associatedKey,
                appName = interaction.appName,
                notificationTitle = interaction.notificationTitle,
                notificationText = interaction.notificationText,
                packageName = interaction.packageName,
                callerPhoneNumber = interaction.callerIdentity?.phoneNumber,
                callerIdentityName = interaction.callerIdentity?.displayName,
                callerIdentityType = interaction.callerIdentity?.identityType,
                callerIdentitySource = interaction.callerIdentity?.source,
                callerIdentityConfidence = interaction.callerIdentity?.confidence,
                callerIdentityIsKnown = interaction.callerIdentity?.isKnown,
                repCategory = interaction.callerReputation?.category,
                repLevel = interaction.callerReputation?.reputationLevel,
                repSpamReports = interaction.callerReputation?.spamReports,
                repFraudReports = interaction.callerReputation?.fraudReports,
                protectionDecision = interaction.protectionDecision,
                incidentType = interaction.incidentType,
                isBlocked = interaction.isBlocked
            )

            val riskAssessmentEntity = interaction.riskAssessment?.let {
                RiskAssessmentEntity(
                    interactionId = interaction.id,
                    score = it.score,
                    riskLevel = it.riskLevel,
                    explanation = it.explanation
                )
            }

            val riskFactorEntities = interaction.riskAssessment?.factors?.map {
                RiskFactorEntity(
                    interactionId = interaction.id,
                    type = it.type,
                    description = it.description,
                    source = it.source,
                    weight = it.weight
                )
            } ?: emptyList()

            val timelineEntities = interaction.timeline.mapIndexed { index, s ->
                TimelineEntryEntity(
                    interactionId = interaction.id,
                    content = s,
                    orderIndex = index
                )
            }

            val evidenceEntities = interaction.evidence.mapIndexed { index, s ->
                EvidenceEntryEntity(
                    interactionId = interaction.id,
                    content = s,
                    orderIndex = index
                )
            }

            database.interactionDao().saveInteractionWithDetails(
                interaction = entity,
                riskAssessment = riskAssessmentEntity,
                riskFactors = riskFactorEntities,
                timelineEntries = timelineEntities,
                evidenceEntries = evidenceEntities
            )
        }
    }

    override suspend fun getInteraction(id: String): Interaction? {
        return null // Not strictly needed for now
    }

    override suspend fun getInteractionByAssociatedKey(key: String): Interaction? {
        // We just need a way to find it. Returning a dummy or implementing full fetch
        val detail = database.interactionDao().getInteractionByAssociatedKey(key)
        if (detail != null) {
            // Reconstruct minimal interaction or full if needed.
            // But InteractionManager expects to find existing in its list. 
            // The existing list has full interactions. 
            // We should use a full fetch, but wait...
            return Interaction(
                id = detail.id,
                title = detail.title,
                timestamp = detail.timestamp,
                timestampMs = detail.timestampMs,
                riskLevel = detail.riskLevel,
                summary = detail.summary,
                associatedKey = detail.associatedKey,
                protectionDecision = detail.protectionDecision,
                incidentType = detail.incidentType,
                isBlocked = detail.isBlocked
            )
        }
        return null
    }

    override suspend fun deleteInteraction(id: String) {
        withContext(Dispatchers.IO) {
            database.interactionDao().deleteInteraction(id)
        }
    }

    override suspend fun getRecentEvents(windowMs: Long): List<SecurityEvent> {
        return withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - windowMs
            val entities = database.securityEventDao().getRecentEvents(cutoff)
            entities.map { entity ->
                val metadataMap = mutableMapOf<String, String>()
                try {
                    val json = JSONObject(entity.metadataString)
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        metadataMap[k] = json.getString(k)
                    }
                } catch (e: Exception) {
                    // Ignore parsing errors
                }
                
                SecurityEvent(
                    id = entity.id,
                    type = entity.type,
                    source = entity.source,
                    timestamp = entity.timestamp,
                    interactionId = entity.interactionId,
                    identity = entity.identity,
                    metadata = metadataMap,
                    initialRisk = entity.initialRisk
                )
            }
        }
    }

    override suspend fun recordEvent(event: SecurityEvent) {
        withContext(Dispatchers.IO) {
            val json = JSONObject()
            for ((k, v) in event.metadata) {
                json.put(k, v)
            }
            
            val entity = SecurityEventEntity(
                id = event.id,
                type = event.type,
                source = event.source,
                timestamp = event.timestamp,
                interactionId = event.interactionId,
                identity = event.identity,
                initialRisk = event.initialRisk,
                metadataString = json.toString()
            )
            database.securityEventDao().insertEvent(entity)
        }
    }

    override suspend fun clearForTesting() {
        withContext(Dispatchers.IO) {
            database.interactionDao().clearAll()
            database.securityEventDao().clearAll()
        }
    }
}
