package com.trustmesh.app.core.identity

enum class IdentitySource {
    LOCAL_CONTACT,
    TRUSTMESH_DIRECTORY,
    EXTERNAL_PROVIDER,
    OEM_METADATA,
    UNKNOWN
}

enum class IdentityType {
    PERSON,
    BUSINESS,
    SPAM,
    UNKNOWN
}

enum class Confidence {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    VERIFIED
}

data class CallerIdentity(
    val phoneNumber: String,
    val displayName: String? = null,
    val identityType: IdentityType = IdentityType.UNKNOWN,
    val confidence: Confidence = Confidence.NONE,
    val source: IdentitySource = IdentitySource.UNKNOWN,
    val isKnown: Boolean = false,
    val isSpam: Boolean = false,
    val isBusiness: Boolean = false,
    val resolvedAt: Long = System.currentTimeMillis()
)

data class ResolvedCaller(
    val identity: CallerIdentity,
    val reputation: CallerReputation? = null
)
