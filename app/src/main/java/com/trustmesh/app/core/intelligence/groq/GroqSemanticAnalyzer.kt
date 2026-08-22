package com.trustmesh.app.core.intelligence.groq

import com.trustmesh.app.core.intelligence.risk.RiskFactor
import com.trustmesh.app.core.intelligence.risk.RiskFactorType

object GroqSemanticAnalyzer {

    fun analyze(groqResponse: GroqAnalysisResponse): List<RiskFactor> {
        val factors = mutableListOf<RiskFactor>()

        if (!groqResponse.isScam && groqResponse.riskScore < 25) {
            return emptyList()
        }

        val confidenceFloat = when (groqResponse.confidence.uppercase()) {
            "HIGH" -> 0.95f
            "MEDIUM" -> 0.75f
            else -> 0.50f
        }

        // 1. Primary Scam Intent Factor
        val weight = (groqResponse.riskScore / 3).coerceIn(10, 40)
        factors.add(
            RiskFactor(
                type = RiskFactorType.GROQ_SEMANTIC_SCAM_INTENT,
                description = "Groq L3 Semantic AI: ${groqResponse.summaryReasoning} [${groqResponse.scamCategory}]",
                weight = weight,
                source = "Groq Semantic LLM",
                confidence = confidenceFloat
            )
        )

        // 2. Psychological Triggers
        for (trigger in groqResponse.psychologicalTriggers) {
            when (trigger.uppercase()) {
                "URGENCY", "PANIC" -> {
                    factors.add(
                        RiskFactor(
                            type = RiskFactorType.GROQ_PSYCHOLOGICAL_URGENCY,
                            description = "Groq L4 Context: Psychological Urgency / Time-Pressure Trigger Detected",
                            weight = 15,
                            source = "Groq Context Engine",
                            confidence = confidenceFloat
                        )
                    )
                }
                "AUTHORITY", "AUTHORITY_FEAR", "IMPERSONATION" -> {
                    factors.add(
                        RiskFactor(
                            type = RiskFactorType.GROQ_AUTHORITY_IMPERSONATION,
                            description = "Groq L4 Context: Authority Impersonation / Law Enforcement Coercion",
                            weight = 20,
                            source = "Groq Context Engine",
                            confidence = confidenceFloat
                        )
                    )
                }
                "FINANCIAL_COERCION", "GREED", "BANKING" -> {
                    factors.add(
                        RiskFactor(
                            type = RiskFactorType.GROQ_FINANCIAL_COERCION,
                            description = "Groq L4 Context: Financial Coercion / Unauthorized Asset Transfer Request",
                            weight = 20,
                            source = "Groq Context Engine",
                            confidence = confidenceFloat
                        )
                    )
                }
            }
        }

        return factors
    }
}
