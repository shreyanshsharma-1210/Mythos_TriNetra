package com.trustmesh.app.core.intelligence.groq

data class GroqAnalysisResponse(
    val isScam: Boolean,
    val scamCategory: String,
    val riskScore: Int,
    val confidence: String,
    val psychologicalTriggers: List<String>,
    val summaryReasoning: String,
    val keySuspiciousPhrases: List<String> = emptyList(),
    val rawJson: String = ""
) {
    companion object {
        fun fallback(reason: String = "Analysis unavailable"): GroqAnalysisResponse {
            return GroqAnalysisResponse(
                isScam = false,
                scamCategory = "UNKNOWN",
                riskScore = 0,
                confidence = "LOW",
                psychologicalTriggers = emptyList(),
                summaryReasoning = reason,
                keySuspiciousPhrases = emptyList()
            )
        }
    }
}
