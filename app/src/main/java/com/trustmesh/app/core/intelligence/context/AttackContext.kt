package com.trustmesh.app.core.intelligence.context

import com.trustmesh.app.core.identity.Confidence

data class AttackContext(
    val contextId: String,
    val relatedInteractionIds: List<String>,
    val detectedPatterns: List<String>,
    val contextType: ContextType,
    val firstSeenTimestamp: Long,
    val lastSeenTimestamp: Long,
    val confidence: Confidence,
    val explanation: String,
    val inferredIntent: InferredIntent
)
