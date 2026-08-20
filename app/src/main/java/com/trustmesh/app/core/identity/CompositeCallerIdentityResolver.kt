package com.trustmesh.app.core.identity

import android.util.Log

class CompositeCallerIdentityResolver(
    private val localResolver: LocalContactIdentityResolver,
    private val externalProvider: ExternalCallerIdentityProvider? = null
) : CallerIdentityResolver {
    
    override suspend fun resolve(phoneNumber: String): ResolvedCaller {
        Log.d("TrustMeshIdentity", "Resolving ${redact(phoneNumber)}")
        
        // 1. Local Contacts
        val localResolvedCaller = localResolver.resolve(phoneNumber)
        val localIdentity = localResolvedCaller.identity
        
        // 2. External Provider (if enabled)
        var reputation: CallerReputation? = null
        var externalIdentity: CallerIdentity? = null

        val isExternalLookupEnabled = com.trustmesh.app.core.settings.TrustMeshSettings.isExternalLookupEnabled.value
        
        if (isExternalLookupEnabled && externalProvider != null) {
            val normalizedNumber = PhoneNumberNormalizer.normalize(phoneNumber)
            
            // Check cache
            reputation = CallerReputationCache.get(normalizedNumber)
            
            if (reputation == null) {
                try {
                    reputation = externalProvider.resolve(normalizedNumber)
                    if (reputation != null) {
                        CallerReputationCache.put(normalizedNumber, reputation)
                    }
                } catch (e: Exception) {
                    Log.e("TrustMeshIdentity", "External provider failed for ${redact(phoneNumber)}", e)
                }
            }

            if (reputation != null && reputation.displayName != null && localIdentity.source != IdentitySource.LOCAL_CONTACT) {
                // If local contact isn't known, but external provides a name, we can use it, but keep source separate
                externalIdentity = CallerIdentity(
                    phoneNumber = phoneNumber,
                    displayName = reputation.displayName,
                    identityType = when(reputation.category) {
                        CallerCategory.BUSINESS, CallerCategory.BANKING, CallerCategory.DELIVERY, CallerCategory.TELECOM -> IdentityType.BUSINESS
                        CallerCategory.SPAM -> IdentityType.SPAM
                        CallerCategory.PERSONAL -> IdentityType.PERSON
                        else -> IdentityType.UNKNOWN
                    },
                    confidence = reputation.confidence,
                    source = IdentitySource.EXTERNAL_PROVIDER,
                    isKnown = reputation.confidence == Confidence.VERIFIED || reputation.confidence == Confidence.HIGH,
                    isSpam = reputation.category == CallerCategory.SPAM || reputation.category == CallerCategory.FRAUD,
                    isBusiness = reputation.category == CallerCategory.BUSINESS || reputation.category == CallerCategory.BANKING
                )
            }
        }
        
        val authoritativeIdentity = when {
            localIdentity.source == IdentitySource.LOCAL_CONTACT -> localIdentity
            externalIdentity != null -> externalIdentity
            else -> localIdentity // Unknown caller
        }

        return ResolvedCaller(authoritativeIdentity, reputation)
    }
    
    private fun redact(number: String): String {
        if (number.length <= 4) return "****"
        return "${number.take(2)}****${number.takeLast(2)}"
    }
}
