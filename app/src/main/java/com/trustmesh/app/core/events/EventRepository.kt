package com.trustmesh.app.core.events

import com.trustmesh.app.interaction.Interaction
import kotlinx.coroutines.flow.Flow

interface EventRepository {
    fun observeInteractions(): Flow<List<Interaction>>
    suspend fun getAllInteractions(): List<Interaction>
    suspend fun insertInteraction(interaction: Interaction)
    suspend fun updateInteraction(interaction: Interaction)
    suspend fun getInteraction(id: String): Interaction?
    suspend fun deleteInteraction(id: String)
    suspend fun getRecentEvents(windowMs: Long): List<SecurityEvent>
    
    // Legacy method for processing pipeline
    suspend fun recordEvent(event: SecurityEvent)
    
    // Helper to get interaction by notification key
    suspend fun getInteractionByAssociatedKey(key: String): Interaction?
    
    suspend fun clearForTesting()
}
