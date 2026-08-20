package com.trustmesh.app.core.identity

data class CallerReputation(
    val phoneNumber: String,
    val displayName: String? = null,
    val category: CallerCategory = CallerCategory.UNKNOWN,
    val reputationLevel: ReputationLevel = ReputationLevel.UNKNOWN,
    val confidence: Confidence = Confidence.NONE,
    val source: String = "Unknown",
    val spamReports: Int = 0,
    val fraudReports: Int = 0,
    val retrievedAt: Long = System.currentTimeMillis()
)
