package com.trustmesh.app.core.identity

interface ExternalCallerIdentityProvider {
    /**
     * Resolves the reputation of a phone number.
     * Implementations should handle all network and API logic, returning a normalized CallerReputation.
     * This method should fail gracefully and not throw exceptions that would crash the pipeline.
     */
    suspend fun resolve(phoneNumber: String): CallerReputation?
}
