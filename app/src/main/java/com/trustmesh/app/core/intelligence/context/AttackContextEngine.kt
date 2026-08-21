package com.trustmesh.app.core.intelligence.context

import com.trustmesh.app.core.events.EventType
import com.trustmesh.app.core.events.SecurityEvent
import com.trustmesh.app.core.identity.Confidence
import com.trustmesh.app.core.intelligence.risk.RiskEngineConfig
import com.trustmesh.app.interaction.Interaction
import java.util.UUID

object AttackContextEngine {

    private val FINANCIAL_KEYWORDS = listOf("bank", "payment", "transaction", "upi", "debited", "credited", "account", "transfer", "money")
    private val OTP_KEYWORDS = listOf("otp", "verification", "verification code", "one-time password", "security code", "login code")
    private val OTP_DIGIT_REGEX = Regex("\\b(\\d{4}|\\d{6})\\b")

    fun evaluateContext(interaction: Interaction, recentEvents: List<SecurityEvent>): AttackContext? {
        val windowMs = RiskEngineConfig.RELATED_EVENT_WINDOW_MS
        val currentTime = System.currentTimeMillis()
        
        val windowEvents = recentEvents.filter { (currentTime - it.timestamp) <= windowMs }.sortedBy { it.timestamp }
        if (windowEvents.isEmpty() && interaction.evidence.isEmpty()) return null

        var hasFinancial = false
        var hasOtp = false
        
        val incomingCalls = windowEvents.filter { it.type == EventType.INCOMING_CALL }
        val repeatedCalls = incomingCalls.groupBy { it.identity }.any { it.value.size > 1 }

        val notifications = windowEvents.filter { it.type == EventType.NOTIFICATION_POSTED }
        
        for (notif in notifications) {
            val title = notif.metadata["title"]?.lowercase() ?: ""
            val text = notif.metadata["text"]?.lowercase() ?: ""
            val fullText = "$title $text"
            
            if (FINANCIAL_KEYWORDS.any { fullText.contains(it) }) {
                hasFinancial = true
            }
            if (OTP_KEYWORDS.any { fullText.contains(it) } || OTP_DIGIT_REGEX.containsMatchIn(fullText)) {
                hasOtp = true
            }
        }
        
        // Also check current interaction if it is a notification
        if (interaction.notificationTitle != null || interaction.notificationText != null) {
            val title = interaction.notificationTitle?.lowercase() ?: ""
            val text = interaction.notificationText?.lowercase() ?: ""
            val fullText = "$title $text"
            if (FINANCIAL_KEYWORDS.any { fullText.contains(it) }) {
                hasFinancial = true
            }
            if (OTP_KEYWORDS.any { fullText.contains(it) } || OTP_DIGIT_REGEX.containsMatchIn(fullText)) {
                hasOtp = true
            }
        }

        val hasIncomingCall = incomingCalls.isNotEmpty() || interaction.evidence.contains("Incoming call")
        val isUnknownCaller = interaction.callerIdentity == null || interaction.callerIdentity?.isKnown != true

        if (!hasIncomingCall || !isUnknownCaller) {
            return null
        }

        val contextType: ContextType
        val inferredIntent: InferredIntent
        val patterns = mutableListOf<String>()
        var explanation = ""

        if (hasFinancial && hasOtp) {
            contextType = ContextType.FINANCIAL_FRAUD
            inferredIntent = InferredIntent.POSSIBLE_FINANCIAL_FRAUD
            patterns.add("Financial Notification")
            patterns.add("OTP Notification")
            patterns.add("Unknown Caller")
            explanation = "Unknown caller interacting during sensitive financial and OTP notifications."
        } else if (hasOtp) {
            contextType = ContextType.OTP_THEFT
            inferredIntent = InferredIntent.POSSIBLE_OTP_THEFT
            patterns.add("OTP Notification")
            patterns.add("Unknown Caller")
            explanation = "Unknown caller attempting to communicate during OTP verification."
        } else if (hasFinancial) {
            contextType = ContextType.FINANCIAL_FRAUD
            inferredIntent = InferredIntent.POSSIBLE_FINANCIAL_FRAUD
            patterns.add("Financial Notification")
            patterns.add("Unknown Caller")
            explanation = "Unknown caller attempting to communicate during sensitive financial activity."
        } else if (repeatedCalls) {
            contextType = ContextType.SOCIAL_ENGINEERING
            inferredIntent = InferredIntent.POSSIBLE_SOCIAL_ENGINEERING
            patterns.add("Repeated Calls")
            patterns.add("Unknown Caller")
            explanation = "Repeated calls from an unknown number in a short period."
        } else {
            return null
        }
        
        val firstSeen = windowEvents.firstOrNull()?.timestamp ?: currentTime
        val lastSeen = windowEvents.lastOrNull()?.timestamp ?: currentTime

        return AttackContext(
            contextId = UUID.randomUUID().toString(),
            relatedInteractionIds = windowEvents.mapNotNull { it.interactionId }.distinct(),
            detectedPatterns = patterns,
            contextType = contextType,
            firstSeenTimestamp = firstSeen,
            lastSeenTimestamp = lastSeen,
            confidence = Confidence.MEDIUM,
            explanation = explanation,
            inferredIntent = inferredIntent
        )
    }
}
