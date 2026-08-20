package com.trustmesh.app.core.intelligence.risk

data class RiskFactor(
    val type: RiskFactorType,
    val description: String,
    val weight: Int,
    val source: String,
    val confidence: Float = 1.0f
)
