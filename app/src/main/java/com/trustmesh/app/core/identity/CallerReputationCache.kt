package com.trustmesh.app.core.identity

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object CallerReputationCache {
    private const val CACHE_EXPIRATION_MS = 24 * 60 * 60 * 1000L // 24 hours

    private data class CacheEntry(
        val reputation: CallerReputation,
        val timestamp: Long
    )

    private val cache = mutableMapOf<String, CacheEntry>()
    private val mutex = Mutex()

    suspend fun get(phoneNumber: String): CallerReputation? = mutex.withLock {
        val entry = cache[phoneNumber] ?: return@withLock null
        if (System.currentTimeMillis() - entry.timestamp > CACHE_EXPIRATION_MS) {
            cache.remove(phoneNumber)
            return@withLock null
        }
        return@withLock entry.reputation
    }

    suspend fun put(phoneNumber: String, reputation: CallerReputation) = mutex.withLock {
        cache[phoneNumber] = CacheEntry(reputation, System.currentTimeMillis())
    }
}
