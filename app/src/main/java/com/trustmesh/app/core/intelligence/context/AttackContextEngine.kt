package com.trustmesh.app.core.intelligence.context

import com.trustmesh.app.core.events.EventType
import com.trustmesh.app.core.events.SecurityEvent
import com.trustmesh.app.core.identity.Confidence
import com.trustmesh.app.core.intelligence.risk.RiskEngineConfig
import com.trustmesh.app.interaction.Interaction
import java.util.UUID

object AttackContextEngine {

    fun evaluateContext(interaction: Interaction, recentEvents: List<SecurityEvent>): AttackContext? {
        val windowMs = RiskEngineConfig.RELATED_EVENT_WINDOW_MS
        val currentTime = System.currentTimeMillis()
        
        val windowEvents = recentEvents.filter { (currentTime - it.timestamp) <= windowMs }.sortedBy { it.timestamp }
        if (windowEvents.isEmpty() && interaction.evidence.isEmpty()) return null

        var hasFinancial = false
        var hasOtp = false
        var hasUrgency = false
        var hasRemoteAccess = false
        var hasGovernment = false
        var hasDelivery = false
        var hasUtility = false
        var hasTelecom = false
        var hasSocialEngineering = false
        var hasLink = false
        var hasCallbackMismatch = false
        var hasPackageAdded = false
        var hasApkRequest = false

        val incomingCalls = windowEvents.filter { it.type == EventType.INCOMING_CALL }
        val repeatedCalls = incomingCalls.groupBy { it.identity }.any { it.value.size > 1 }

        val notifications = windowEvents.filter { it.type == EventType.NOTIFICATION_POSTED }
        
        fun processText(title: String, text: String) {
            val intentSignals = SmsIntentClassifier.classify(text, title)
            val urlSignals = UrlAnalyzer.analyze(text)
            val allSignals = intentSignals + urlSignals
            
            for (signal in allSignals) {
                when (signal.type.category) {
                    ScamSignalCategory.FINANCIAL -> hasFinancial = true
                    ScamSignalCategory.AUTHENTICATION -> hasOtp = true
                    ScamSignalCategory.REMOTE_ACCESS -> {
                        hasRemoteAccess = true
                        if (signal.type == ScamSignalType.APK_INSTALL_REQUEST) hasApkRequest = true
                    }
                    ScamSignalCategory.URGENCY -> hasUrgency = true
                    ScamSignalCategory.GOVERNMENT -> hasGovernment = true
                    ScamSignalCategory.DELIVERY -> hasDelivery = true
                    ScamSignalCategory.UTILITY -> hasUtility = true
                    ScamSignalCategory.TELECOM -> hasTelecom = true
                    ScamSignalCategory.SOCIAL_ENGINEERING -> hasSocialEngineering = true
                    ScamSignalCategory.LINK -> hasLink = true
                    else -> {}
                }
            }
        }

        for (notif in notifications) {
            val title = notif.metadata["title"] ?: ""
            val text = notif.metadata["text"] ?: ""
            processText(title, text)
        }
        
        // Also check current interaction if it is a notification
        if (interaction.notificationTitle != null || interaction.notificationText != null) {
            val title = interaction.notificationTitle ?: ""
            val text = interaction.notificationText ?: ""
            processText(title, text)
        }
        
        // Check for specific events like PACKAGE_ADDED
        val systemEvents = windowEvents.filter { it.type == EventType.SYSTEM_EVENT }
        if (systemEvents.any { it.metadata["action"] == "PACKAGE_ADDED" }) {
            hasPackageAdded = true
        }
        
        val incomingCaller = interaction.associatedKey ?: interaction.callerIdentity?.phoneNumber
        if (incomingCaller != null) {
            fun checkCallback(title: String, text: String) {
                val intentSignals = SmsIntentClassifier.classify(text, title)
                val hasCallbackIntent = intentSignals.any { it.type == ScamSignalType.CALL_THIS_NUMBER || it.type == ScamSignalType.CONTACT_AGENT }
                val extractedNumbers = PhoneNumberExtractor.extract(text)
                if (hasCallbackIntent && extractedNumbers.isNotEmpty()) {
                    if (extractedNumbers.first() != incomingCaller) {
                        hasCallbackMismatch = true
                    }
                }
            }
            for (notif in notifications) {
                checkCallback(notif.metadata["title"] ?: "", notif.metadata["text"] ?: "")
            }
            if (interaction.notificationTitle != null || interaction.notificationText != null) {
                checkCallback(interaction.notificationTitle ?: "", interaction.notificationText ?: "")
            }
        }

        val hasActiveCall = incomingCalls.isNotEmpty() || 
                            windowEvents.any { it.type == EventType.OUTGOING_CALL } || 
                            interaction.evidence.contains("Incoming call") || 
                            interaction.evidence.contains("Outgoing call") || 
                            interaction.appName == "Phone" || 
                            interaction.title.contains("call", ignoreCase = true)
        val isUnknownCaller = interaction.callerIdentity == null || interaction.callerIdentity?.isKnown != true

        if (!hasActiveCall || !isUnknownCaller) {
            return null
        }

        val contextType: ContextType
        val inferredIntent: InferredIntent
        val patterns = mutableListOf<String>()
        var explanation = ""

        if (hasRemoteAccess || (hasApkRequest && hasPackageAdded)) {
            contextType = ContextType.REMOTE_ACCESS_SCAM
            inferredIntent = InferredIntent.POSSIBLE_REMOTE_ACCESS_SCAM
            patterns.add(if (hasPackageAdded) "Package Installation Correlation" else "Remote Access Request")
            explanation = if (hasPackageAdded) "A new package was installed shortly after an APK installation request from an unknown caller." else "Unknown caller attempting to establish remote access or screen sharing."
        } else if (hasGovernment && (hasFinancial || hasUrgency || hasSocialEngineering)) {
            contextType = ContextType.GOVERNMENT_IMPERSONATION
            inferredIntent = InferredIntent.POSSIBLE_GOVERNMENT_IMPERSONATION
            patterns.add("Authority Impersonation")
            explanation = "Caller impersonating government authority to extract funds or information."
        } else if (hasDelivery && (hasLink || hasFinancial)) {
            contextType = ContextType.PARCEL_SCAM
            inferredIntent = InferredIntent.POSSIBLE_PARCEL_SCAM
            patterns.add("Parcel Context")
            explanation = "Caller using fake package delivery alerts to solicit clicks or payments."
        } else if (hasUtility && (hasUrgency || hasSocialEngineering)) {
            contextType = ContextType.UTILITY_SCAM
            inferredIntent = InferredIntent.POSSIBLE_UTILITY_SCAM
            patterns.add("Utility Disconnection Threat")
            explanation = "Caller threatening utility disconnection to solicit urgent payment."
        } else if (hasTelecom && (hasUrgency || hasLink)) {
            contextType = ContextType.TELECOM_IMPERSONATION
            inferredIntent = InferredIntent.POSSIBLE_TELECOM_IMPERSONATION
            patterns.add("KYC / SIM Block Threat")
            explanation = "Caller impersonating telecom provider to steal credentials or mandate KYC."
        } else if (hasFinancial && hasOtp) {
            contextType = ContextType.FINANCIAL_FRAUD
            inferredIntent = InferredIntent.POSSIBLE_FINANCIAL_FRAUD
            patterns.add("Financial Notification")
            patterns.add("OTP Notification")
            if (hasCallbackMismatch) patterns.add("Callback Mismatch")
            explanation = "Unknown caller interacting during sensitive financial and OTP notifications." + if (hasCallbackMismatch) " Contains suspicious callback mismatch." else ""
        } else if (hasOtp) {
            contextType = ContextType.OTP_THEFT
            inferredIntent = InferredIntent.POSSIBLE_OTP_THEFT
            patterns.add("OTP Notification")
            explanation = "Unknown caller attempting to communicate during OTP verification."
        } else if (hasFinancial) {
            contextType = ContextType.FINANCIAL_FRAUD
            inferredIntent = InferredIntent.POSSIBLE_FINANCIAL_FRAUD
            patterns.add("Financial Notification")
            explanation = "Unknown caller attempting to communicate during sensitive financial activity."
        } else if (repeatedCalls) {
            contextType = ContextType.SOCIAL_ENGINEERING
            inferredIntent = InferredIntent.POSSIBLE_SOCIAL_ENGINEERING
            patterns.add("Repeated Calls")
            if (hasCallbackMismatch) patterns.add("Callback Mismatch")
            explanation = "Repeated calls from an unknown number in a short period." + if (hasCallbackMismatch) " Contains suspicious callback mismatch." else ""
        } else if (hasCallbackMismatch) {
            contextType = ContextType.SOCIAL_ENGINEERING
            inferredIntent = InferredIntent.POSSIBLE_SOCIAL_ENGINEERING
            patterns.add("Callback Mismatch")
            explanation = "Unknown caller context with a suspicious callback number mismatch in recent messages."
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
