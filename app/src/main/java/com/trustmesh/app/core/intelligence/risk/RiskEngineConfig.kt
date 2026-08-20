package com.trustmesh.app.core.intelligence.risk

object RiskEngineConfig {
    const val RELATED_EVENT_WINDOW_MS = 300_000L // 5 minutes
    const val REPEATED_CALL_WINDOW_MS = 3_600_000L // 1 hour
    const val MAXIMUM_EVIDENCE_AGE_MS = 86_400_000L // 24 hours
    
    // Scoring Thresholds
    const val THRESHOLD_ELEVATED = 25
    const val THRESHOLD_HIGH = 50
    const val THRESHOLD_CRITICAL = 75
    
    // Default weights
    const val WEIGHT_UNKNOWN_CALLER = 10
    const val WEIGHT_IDENTITY_UNRESOLVED = 5
    const val WEIGHT_SECURITY_NOTIFICATION = 30
    const val WEIGHT_FINANCIAL_NOTIFICATION = 25
    const val WEIGHT_SUSPICIOUS_NOTIFICATION = 20
    const val WEIGHT_MULTIPLE_RELATED_EVENTS = 15
    const val WEIGHT_RAPID_EVENT_SEQUENCE = 20
    const val WEIGHT_REPEATED_CALL = 15
    const val WEIGHT_EXTERNAL_HIGH_RISK_REPUTATION = 40
    const val WEIGHT_EXTERNAL_FRAUD_REPORT = 50
    const val WEIGHT_EXTERNAL_SPAM_REPORT = 20
    const val WEIGHT_KNOWN_BUSINESS_IDENTITY = -10 // Reduces risk slightly
    const val WEIGHT_EXTERNAL_IDENTITY_CONFIDENCE = -5
}
