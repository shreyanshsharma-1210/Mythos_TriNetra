package com.trustmesh.app.core.intelligence.risk

import com.trustmesh.app.core.events.SecurityEvent
import com.trustmesh.app.core.events.EventType
import com.trustmesh.app.interaction.Interaction

object EvidenceFusionEngine {
    
    fun fuseEvidence(interaction: Interaction, recentEvents: List<SecurityEvent>): List<RiskFactor> {
        val factors = mutableListOf<RiskFactor>()
        val currentTime = System.currentTimeMillis()
        
        // 1. Identity related factors
        val identity = interaction.callerIdentity
        if (identity?.isKnown == true) {
            factors.add(RiskFactor(
                type = RiskFactorType.KNOWN_CONTACT,
                description = "Caller is a known contact",
                weight = 0, // Known contacts have baseline 0 added risk, but don't reset to 0 inherently if other evidence exists
                source = "Identity Resolver"
            ))
        } else {
            factors.add(RiskFactor(
                type = RiskFactorType.UNKNOWN_CALLER,
                description = "Caller identity could not be independently resolved as known",
                weight = RiskEngineConfig.WEIGHT_UNKNOWN_CALLER,
                source = "Identity Resolver"
            ))
            
            if (identity == null || identity.source == com.trustmesh.app.core.identity.IdentitySource.UNKNOWN) {
                factors.add(RiskFactor(
                    type = RiskFactorType.IDENTITY_UNRESOLVED,
                    description = "Identity unresolved",
                    weight = RiskEngineConfig.WEIGHT_IDENTITY_UNRESOLVED,
                    source = "Identity Resolver"
                ))
            }
        }
        
        // 2. Notification events
        val notifications = recentEvents.filter { it.type == EventType.NOTIFICATION_POSTED }
        var hasSecurityNotification = false
        for (notif in notifications) {
            val title = notif.metadata["title"] ?: ""
            val text = notif.metadata["text"] ?: ""
            
            val intentSignals = com.trustmesh.app.core.intelligence.context.SmsIntentClassifier.classify(text, title)
            val urlSignals = com.trustmesh.app.core.intelligence.context.UrlAnalyzer.analyze(text)
            
            val allSignals = intentSignals + urlSignals
            
            for (signal in allSignals) {
                val factorType = when (signal.type.category) {
                    com.trustmesh.app.core.intelligence.context.ScamSignalCategory.FINANCIAL -> RiskFactorType.INTENT_FINANCIAL
                    com.trustmesh.app.core.intelligence.context.ScamSignalCategory.AUTHENTICATION -> RiskFactorType.INTENT_OTP_VERIFICATION
                    com.trustmesh.app.core.intelligence.context.ScamSignalCategory.REMOTE_ACCESS -> RiskFactorType.INTENT_REMOTE_ACCESS
                    com.trustmesh.app.core.intelligence.context.ScamSignalCategory.URGENCY -> RiskFactorType.INTENT_URGENCY
                    com.trustmesh.app.core.intelligence.context.ScamSignalCategory.SOCIAL_ENGINEERING -> RiskFactorType.INTENT_SOCIAL_ENGINEERING
                    com.trustmesh.app.core.intelligence.context.ScamSignalCategory.TELECOM,
                    com.trustmesh.app.core.intelligence.context.ScamSignalCategory.UTILITY,
                    com.trustmesh.app.core.intelligence.context.ScamSignalCategory.DELIVERY,
                    com.trustmesh.app.core.intelligence.context.ScamSignalCategory.GOVERNMENT -> RiskFactorType.INTENT_THREAT_OR_SUSPENSION
                    com.trustmesh.app.core.intelligence.context.ScamSignalCategory.LINK -> RiskFactorType.INTENT_MALICIOUS_LINK
                    else -> RiskFactorType.SUSPICIOUS_NOTIFICATION
                }
                
                factors.add(RiskFactor(
                    type = factorType,
                    description = signal.explanation,
                    weight = signal.weight,
                    source = signal.source,
                    confidence = when(signal.confidence) {
                        com.trustmesh.app.core.intelligence.context.Confidence.HIGH -> 1.0f
                        com.trustmesh.app.core.intelligence.context.Confidence.MEDIUM -> 0.7f
                        com.trustmesh.app.core.intelligence.context.Confidence.LOW -> 0.4f
                    }
                ))
                
                if (signal.weight >= 10 || signal.type.category == com.trustmesh.app.core.intelligence.context.ScamSignalCategory.AUTHENTICATION) {
                    hasSecurityNotification = true
                }
            }
            
            // Extract callback number
            val extractedNumbers = com.trustmesh.app.core.intelligence.context.PhoneNumberExtractor.extract(text)
            val hasCallbackIntent = intentSignals.any { it.type == com.trustmesh.app.core.intelligence.context.ScamSignalType.CALL_THIS_NUMBER || it.type == com.trustmesh.app.core.intelligence.context.ScamSignalType.CONTACT_AGENT }
            
            if (hasCallbackIntent && extractedNumbers.isNotEmpty()) {
                val extracted = extractedNumbers.first()
                factors.add(RiskFactor(
                    type = RiskFactorType.CALLBACK_NUMBER,
                    description = "Message instructs to contact a specific number",
                    weight = 5,
                    source = "Callback Intelligence"
                ))
                
                val incomingCaller = interaction.associatedKey ?: interaction.callerIdentity?.phoneNumber
                if (incomingCaller != null && incomingCaller != extracted) {
                    factors.add(RiskFactor(
                        type = RiskFactorType.CALLBACK_NUMBER_MISMATCH,
                        description = "Callback number in message does not match incoming caller",
                        weight = 15,
                        source = "Callback Intelligence"
                    ))
                }
            }
        }
        
        // Correlate APK install request with PACKAGE_ADDED
        val hasApkRequest = factors.any { it.type == RiskFactorType.INTENT_REMOTE_ACCESS }
        if (hasApkRequest) {
            val packageAddedEvents = recentEvents.filter { 
                it.type == EventType.SYSTEM_EVENT && it.metadata["action"] == "PACKAGE_ADDED" 
            }
            if (packageAddedEvents.isNotEmpty()) {
                factors.add(RiskFactor(
                    type = RiskFactorType.PACKAGE_ADDED,
                    description = "A new package was installed shortly after an APK installation request",
                    weight = 25,
                    source = "Package Event Sensor"
                ))
            }
        }
        
        // 3. Repeated events & correlation
        val incomingCalls = recentEvents.filter { it.type == EventType.INCOMING_CALL && it.identity == interaction.associatedKey }
        if (incomingCalls.size >= 2 && identity?.isKnown != true) {
            factors.add(RiskFactor(
                type = RiskFactorType.REPEATED_CALL,
                description = "Repeated calls from this unknown number",
                weight = RiskEngineConfig.WEIGHT_REPEATED_CALL,
                source = "Call Sensor"
            ))
        }
        
        // Multiple related events
        // If we have an incoming call AND a security notification within the window
        val hasIncomingCall = recentEvents.any { it.type == EventType.INCOMING_CALL } || interaction.evidence.contains("Incoming call")
        if (hasIncomingCall && hasSecurityNotification) {
            factors.add(RiskFactor(
                type = RiskFactorType.MULTIPLE_RELATED_EVENTS,
                description = "Multiple related security signals detected within a short window",
                weight = RiskEngineConfig.WEIGHT_MULTIPLE_RELATED_EVENTS,
                source = "Evidence Fusion"
            ))
        }
        // 4. External Reputation
        val reputation = interaction.callerReputation
        if (reputation != null) {
            when (reputation.reputationLevel) {
                com.trustmesh.app.core.identity.ReputationLevel.HIGH_RISK -> {
                    factors.add(RiskFactor(
                        type = RiskFactorType.EXTERNAL_HIGH_RISK_REPUTATION,
                        description = "High risk reputation identified by external directory",
                        weight = RiskEngineConfig.WEIGHT_EXTERNAL_HIGH_RISK_REPUTATION,
                        source = "External Directory"
                    ))
                }
                com.trustmesh.app.core.identity.ReputationLevel.SUSPICIOUS -> {
                    if (reputation.category == com.trustmesh.app.core.identity.CallerCategory.SPAM) {
                        factors.add(RiskFactor(
                            type = RiskFactorType.EXTERNAL_SPAM_REPORT,
                            description = "Caller frequently reported for spam",
                            weight = RiskEngineConfig.WEIGHT_EXTERNAL_SPAM_REPORT,
                            source = "External Directory"
                        ))
                    }
                }
                else -> {}
            }
            
            if (reputation.category == com.trustmesh.app.core.identity.CallerCategory.FRAUD || reputation.fraudReports > 0) {
                factors.add(RiskFactor(
                    type = RiskFactorType.EXTERNAL_FRAUD_REPORT,
                    description = "Caller has existing fraud reports",
                    weight = RiskEngineConfig.WEIGHT_EXTERNAL_FRAUD_REPORT,
                    source = "External Directory"
                ))
            }
            
            if (reputation.category == com.trustmesh.app.core.identity.CallerCategory.BUSINESS || reputation.category == com.trustmesh.app.core.identity.CallerCategory.BANKING) {
                if (reputation.confidence == com.trustmesh.app.core.identity.Confidence.VERIFIED) {
                    factors.add(RiskFactor(
                        type = RiskFactorType.KNOWN_BUSINESS_IDENTITY,
                        description = "Verified business identity",
                        weight = RiskEngineConfig.WEIGHT_KNOWN_BUSINESS_IDENTITY,
                        source = "External Directory"
                    ))
                }
            }
        }
        
        // 5. Groq Semantic AI Intelligence Integration
        val groqResp = interaction.groqResponse
        if (groqResp != null) {
            val groqFactors = com.trustmesh.app.core.intelligence.groq.GroqSemanticAnalyzer.analyze(groqResp)
            factors.addAll(groqFactors)
        }

        return factors
    }
}

