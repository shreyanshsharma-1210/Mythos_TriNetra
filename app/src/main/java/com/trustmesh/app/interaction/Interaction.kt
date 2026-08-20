package com.trustmesh.app.interaction

import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.core.identity.CallerIdentity
import com.trustmesh.app.core.intelligence.risk.RiskAssessment

data class Interaction(
    val id: String,
    val title: String,
    val timestamp: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val riskLevel: RiskLevel,
    val summary: String,
    val evidence: List<String> = emptyList(),
    val timeline: List<String> = emptyList(),
    val associatedKey: String? = null,
    val appName: String? = null,
    val notificationTitle: String? = null,
    val notificationText: String? = null,
    val packageName: String? = null,
    val callerIdentity: CallerIdentity? = null,
    val callerReputation: com.trustmesh.app.core.identity.CallerReputation? = null,
    val riskAssessment: RiskAssessment? = null
)
