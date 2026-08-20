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
            val title = notif.metadata["title"]?.lowercase() ?: ""
            val text = notif.metadata["text"]?.lowercase() ?: ""
            
            // Very simple heuristic rules for Phase 7
            if (title.contains("bank") || title.contains("payment") || title.contains("transfer") || title.contains("upi") || title.contains("transaction") || text.contains("otp")) {
                factors.add(RiskFactor(
                    type = RiskFactorType.FINANCIAL_NOTIFICATION,
                    description = "Financial notification observed",
                    weight = RiskEngineConfig.WEIGHT_FINANCIAL_NOTIFICATION,
                    source = "Notification Sensor"
                ))
                hasSecurityNotification = true
            } else if (title.contains("security") || title.contains("alert") || title.contains("login") || title.contains("password")) {
                factors.add(RiskFactor(
                    type = RiskFactorType.SECURITY_NOTIFICATION,
                    description = "Security-related notification observed",
                    weight = RiskEngineConfig.WEIGHT_SECURITY_NOTIFICATION,
                    source = "Notification Sensor"
                ))
                hasSecurityNotification = true
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
        
        return factors
    }
}
