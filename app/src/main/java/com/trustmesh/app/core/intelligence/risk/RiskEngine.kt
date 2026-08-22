package com.trustmesh.app.core.intelligence.risk

import com.trustmesh.app.core.events.SecurityEvent
import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.interaction.Interaction
import android.util.Log

import com.trustmesh.app.core.intelligence.context.AttackContextEngine
import com.trustmesh.app.core.intelligence.context.InferredIntent
import java.util.concurrent.ConcurrentHashMap

object RiskEngine {
    private const val TAG = "TrustMeshRisk"

    // Thread-safe cache to avoid redundant risk engine calculations during bursts
    private val assessmentCache = ConcurrentHashMap<String, Pair<Int, RiskAssessment>>()

    fun evaluate(interaction: Interaction, recentEvents: List<SecurityEvent>): RiskAssessment {
        val inputHash = calculateInputHash(interaction, recentEvents)
        val cached = assessmentCache[interaction.id]
        if (cached != null && cached.first == inputHash) {
            Log.d(TAG, "Risk evaluation cache HIT for interaction ${interaction.id}")
            return cached.second
        }

        Log.d(TAG, "Risk evaluation started (cache MISS) for interaction ${interaction.id}")
        
        val factors = EvidenceFusionEngine.fuseEvidence(interaction, recentEvents)
        var totalScore = 0
        
        val evidenceDescriptions = mutableListOf<String>()
        
        for (factor in factors) {
            totalScore += factor.weight
            evidenceDescriptions.add(factor.description)
            Log.d(TAG, "Risk factor added: ${factor.type} +${factor.weight}")
        }
        
        val attackContext = AttackContextEngine.evaluateContext(interaction, recentEvents)
        if (attackContext != null) {
            totalScore += 20 // Additional weight for correlated attack context
            evidenceDescriptions.add("Correlated Context: ${attackContext.contextType.name}")
            Log.d(TAG, "AttackContext inferred: ${attackContext.contextType}")
        }
        
        // Clamp score between 0 and 100
        val clampedScore = totalScore.coerceIn(0, 100)
        Log.d(TAG, "Risk score: $clampedScore")
        
        val riskLevel = when (clampedScore) {
            in 0..24 -> RiskLevel.LOW
            in 25..49 -> RiskLevel.ELEVATED
            in 50..74 -> RiskLevel.HIGH
            else -> RiskLevel.CRITICAL
        }
        
        Log.d(TAG, "Risk level: $riskLevel")
        
        val explanation = buildString {
            if (factors.isEmpty() && attackContext == null) {
                append("No suspicious evidence detected.")
            } else {
                append("Evaluated ${factors.size} factor(s) indicating potential risk. ")
                if (attackContext != null) {
                    append(attackContext.explanation)
                }
            }
        }
        
        val result = RiskAssessment(
            riskLevel = riskLevel,
            score = clampedScore,
            evidence = evidenceDescriptions,
            factors = factors,
            explanation = explanation,
            attackContext = attackContext,
            inferredIntent = attackContext?.inferredIntent ?: InferredIntent.UNKNOWN
        )

        assessmentCache[interaction.id] = Pair(inputHash, result)
        return result
    }

    private fun calculateInputHash(interaction: Interaction, recentEvents: List<SecurityEvent>): Int {
        var result = interaction.id.hashCode()
        result = 31 * result + interaction.title.hashCode()
        result = 31 * result + interaction.evidence.hashCode()
        result = 31 * result + (interaction.associatedKey?.hashCode() ?: 0)
        result = 31 * result + (interaction.appName?.hashCode() ?: 0)
        result = 31 * result + (interaction.notificationTitle?.hashCode() ?: 0)
        result = 31 * result + (interaction.notificationText?.hashCode() ?: 0)
        result = 31 * result + (interaction.packageName?.hashCode() ?: 0)
        result = 31 * result + (interaction.callerIdentity?.hashCode() ?: 0)
        result = 31 * result + (interaction.callerReputation?.hashCode() ?: 0)
        
        // Hash of relevant recentEvents elements to capture burst changes
        for (event in recentEvents) {
            result = 31 * result + event.id.hashCode()
            result = 31 * result + event.timestamp.hashCode()
            result = 31 * result + event.type.hashCode()
            result = 31 * result + event.metadata.hashCode()
        }
        return result
    }

    /** Invalidate a specific interaction's cache entry */
    fun invalidateCache(interactionId: String) {
        assessmentCache.remove(interactionId)
    }

    /**
     * Drops every memoized assessment.
     *
     * Called when a new call starts. The cache exists to avoid recomputing the same inputs during a
     * burst, not to carry a verdict between calls — a fresh caller must be scored from zero rather
     * than inheriting the last call's number, which is what a stale entry surviving into the next
     * call would produce.
     */
    fun resetForNewCall() {
        val size = assessmentCache.size
        assessmentCache.clear()
        Log.d(TAG, "Assessment cache cleared for new call ($size entr${if (size == 1) "y" else "ies"} dropped)")
    }

    /** Clears the memoized assessments (used during unit testing to avoid cross-test pollution) */
    fun clearCacheForTesting() {
        assessmentCache.clear()
    }
}
