package com.trustmesh.app.core.identity

interface CallerIdentityResolver {
    suspend fun resolve(phoneNumber: String): ResolvedCaller
}
