package com.trustmesh.app.core.intelligence.risk

import com.trustmesh.app.core.events.RiskLevel

import com.trustmesh.app.core.intelligence.context.AttackContext
import com.trustmesh.app.core.intelligence.context.InferredIntent

data class RiskAssessment(
    val riskLevel: RiskLevel,
    val score: Int,
    val evidence: List<String>,
    val factors: List<RiskFactor>,
    val evaluatedAt: Long = System.currentTimeMillis(),
    val explanation: String,
    val attackContext: AttackContext? = null,
    val inferredIntent: InferredIntent = InferredIntent.UNKNOWN
)
