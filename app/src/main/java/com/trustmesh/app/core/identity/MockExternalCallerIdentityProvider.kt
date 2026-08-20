package com.trustmesh.app.core.identity

import kotlinx.coroutines.delay

class MockExternalCallerIdentityProvider : ExternalCallerIdentityProvider {
    override suspend fun resolve(phoneNumber: String): CallerReputation? {
        // Simulate network delay
        delay(500)
        
        val normalized = PhoneNumberNormalizer.normalize(phoneNumber)
        
        return when {
            normalized.endsWith("000") -> CallerReputation(
                phoneNumber = phoneNumber,
                displayName = "Fraud Ring A",
                category = CallerCategory.FRAUD,
                reputationLevel = ReputationLevel.HIGH_RISK,
                confidence = Confidence.HIGH,
                source = "MockProvider",
                fraudReports = 150
            )
            normalized.endsWith("111") -> CallerReputation(
                phoneNumber = phoneNumber,
                displayName = "Legitimate Bank",
                category = CallerCategory.BANKING,
                reputationLevel = ReputationLevel.NEUTRAL,
                confidence = Confidence.VERIFIED,
                source = "MockProvider"
            )
            normalized.endsWith("222") -> CallerReputation(
                phoneNumber = phoneNumber,
                displayName = "Spam Telemarketer",
                category = CallerCategory.SPAM,
                reputationLevel = ReputationLevel.SUSPICIOUS,
                confidence = Confidence.MEDIUM,
                source = "MockProvider",
                spamReports = 45
            )
            normalized.endsWith("999") -> {
                // Simulate failure
                null
            }
            else -> CallerReputation(
                phoneNumber = phoneNumber,
                displayName = null,
                category = CallerCategory.UNKNOWN,
                reputationLevel = ReputationLevel.UNKNOWN,
                confidence = Confidence.LOW,
                source = "MockProvider"
            )
        }
    }
}
