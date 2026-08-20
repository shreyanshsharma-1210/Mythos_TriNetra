package com.trustmesh.app.interaction

import android.content.Context
import android.util.Log
import com.trustmesh.app.core.events.EventType
import com.trustmesh.app.core.events.SecurityEvent
import com.trustmesh.app.core.identity.CompositeCallerIdentityResolver
import com.trustmesh.app.core.identity.LocalContactIdentityResolver
import com.trustmesh.app.core.identity.MockExternalCallerIdentityProvider
import com.trustmesh.app.core.incident.SecurityIncidentManager
import com.trustmesh.app.core.intelligence.risk.RiskEngine
import com.trustmesh.app.core.intelligence.risk.RiskEngineConfig
import com.trustmesh.app.data.local.TrustMeshDatabase
import com.trustmesh.app.data.repository.RoomEventRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val TAG = "TrustMeshInteraction"

/**
 * Phase 13 hardening:
 *
 * 1. SupervisorJob so one failing coroutine does not cancel the others.
 *
 * 2. init() is re-entrant — calling it multiple times from different services is safe.
 *    The repository and resolver are only created once (first-caller-wins).
 *
 * 3. processEvent() never blocks the caller's thread. All Room I/O and risk
 *    evaluation happen inside Dispatchers.IO coroutines.
 *
 * 4. Startup hydration guard: interactions already populated from Room are not
 *    overwritten with an empty list on subsequent init() calls.
 *
 * 5. Identity resolution race condition is mitigated: we re-fetch the current index
 *    inside the coroutine (after await) so a stale captured index is not used.
 *
 * 6. evaluateRisk() re-fetches the latest interaction list index rather than relying
 *    on a captured index to avoid stale-capture races.
 *
 * 7. All exceptions inside coroutines are caught and logged — they must not silently
 *    propagate and cancel the SupervisorJob child.
 */
object InteractionManager {
    private val _interactions = MutableStateFlow<List<Interaction>>(emptyList())
    val interactions: StateFlow<List<Interaction>> = _interactions.asStateFlow()

    private var repository: RoomEventRepository? = null

    // SupervisorJob: one failed child coroutine does not cancel siblings
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    internal var identityResolver: CompositeCallerIdentityResolver? = null

    // Guard: init() is safe to call multiple times (e.g., from both services)
    @Volatile private var initialized = false

    // Lock object for thread-safe state flow updates
    private val lock = Any()

    fun init(context: Context) {
        if (initialized) {
            Log.d(TAG, "Already initialized — skipping re-init")
            return
        }
        synchronized(this) {
            if (initialized) return
            initialized = true
        }

        try {
            val appContext = context.applicationContext
            identityResolver = CompositeCallerIdentityResolver(
                localResolver = LocalContactIdentityResolver(appContext),
                externalProvider = MockExternalCallerIdentityProvider()
            )

            val db = TrustMeshDatabase.getDatabase(appContext)
            val repo = RoomEventRepository(db)
            repository = repo

            // Hydrate from Room on startup — only if not already loaded
            scope.launch {
                try {
                    val initial = repo.getAllInteractions()
                    synchronized(lock) {
                        if (_interactions.value.isEmpty() && initial.isNotEmpty()) {
                            _interactions.value = initial
                            Log.i(TAG, "Hydrated ${initial.size} interactions from Room")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Room hydration failed — starting with empty list", e)
                }
            }

            Log.i(TAG, "Initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            initialized = false  // Allow retry
        }
    }

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    fun processEvent(event: SecurityEvent) {
        // Record the raw event asynchronously — does not block the caller
        scope.launch {
            try {
                repository?.recordEvent(event)
            } catch (e: Exception) {
                Log.e(TAG, "recordEvent failed", e)
            }
        }

        val timeString = timeFormat.format(Date(event.timestamp))

        // ── Notification deduplication ──────────────────────────────────────
        if (event.type == EventType.NOTIFICATION_POSTED) {
            val notifKey = event.metadata["notificationKey"]
            if (!notifKey.isNullOrEmpty()) {
                var updatedItem: Interaction? = null
                var deduplicated = false

                synchronized(lock) {
                    val existingList = _interactions.value
                    val existingIndex = existingList.indexOfFirst { it.associatedKey == notifKey }
                    if (existingIndex != -1) {
                        val existing = existingList[existingIndex]
                        val appName = event.metadata["appName"] ?: event.identity ?: "Unknown App"
                        val notifTitle = event.metadata["title"] ?: ""
                        val notifText = event.metadata["text"] ?: ""
                        val packageName = event.metadata["packageName"] ?: ""
                        val titleAvailable = notifTitle.isNotBlank()
                        val textAvailable = notifText.isNotBlank()

                        val newEvidence = mutableListOf("Notification posted", "App: $appName")
                        if (titleAvailable) newEvidence.add("Title available")
                        if (textAvailable) newEvidence.add("Content available")

                        val updatedInteraction = existing.copy(
                            timestamp = timeString,
                            timestampMs = System.currentTimeMillis(),
                            evidence = newEvidence,
                            timeline = existing.timeline + "$timeString - Notification content updated",
                            appName = appName,
                            notificationTitle = notifTitle.ifEmpty { existing.notificationTitle },
                            notificationText = notifText.ifEmpty { existing.notificationText },
                            packageName = packageName
                        )

                        val newList = existingList.toMutableList()
                        newList[existingIndex] = updatedInteraction
                        val movedItem = newList.removeAt(existingIndex)
                        newList.add(0, movedItem)
                        _interactions.value = newList
                        updatedItem = movedItem
                        deduplicated = true
                    }
                }

                if (deduplicated && updatedItem != null) {
                    val item = updatedItem!!
                    scope.launch {
                        try {
                            repository?.insertInteraction(item)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to persist updated notification interaction", e)
                        }
                    }
                    evaluateRisk(item.id)
                    Log.d(TAG, "Notification deduplication: updated existing interaction for key=$notifKey")
                    return
                }
            }
        }

        // ── New interaction ────────────────────────────────────────────────
        val interactionId = event.interactionId ?: UUID.randomUUID().toString()

        val newInteraction = when (event.type) {
            EventType.INCOMING_CALL -> {
                val number = event.identity ?: "Unknown Caller"
                Interaction(
                    id = interactionId,
                    title = number,
                    timestamp = timeString,
                    riskLevel = event.initialRisk,
                    summary = "Incoming call observed by TrustMesh",
                    evidence = listOf("Incoming call", "Caller metadata available"),
                    timeline = listOf(
                        "$timeString - Incoming call detected",
                        "$timeString - TrustMesh began monitoring"
                    )
                )
            }
            EventType.NOTIFICATION_POSTED -> {
                val notifKey = event.metadata["notificationKey"]
                val notifTitle = event.metadata["title"] ?: ""
                val notifText = event.metadata["text"] ?: ""
                val appName = event.metadata["appName"] ?: event.identity ?: "Unknown App"
                val packageName = event.metadata["packageName"] ?: ""
                val evidence = mutableListOf("Notification posted", "App: $appName").apply {
                    if (notifTitle.isNotBlank()) add("Title available")
                    if (notifText.isNotBlank()) add("Content available")
                }
                Interaction(
                    id = interactionId,
                    title = appName,
                    timestamp = timeString,
                    riskLevel = event.initialRisk,
                    summary = "Notification observed",
                    evidence = evidence,
                    timeline = listOf("$timeString - Notification received"),
                    associatedKey = notifKey,
                    appName = appName,
                    notificationTitle = notifTitle,
                    notificationText = notifText,
                    packageName = packageName
                )
            }
            else -> Interaction(
                id = interactionId,
                title = "Unknown Event",
                timestamp = timeString,
                riskLevel = event.initialRisk,
                summary = "Event observed",
                evidence = emptyList(),
                timeline = listOf("$timeString - Event observed")
            )
        }

        synchronized(lock) {
            _interactions.value = listOf(newInteraction) + _interactions.value
        }

        scope.launch {
            try {
                repository?.insertInteraction(newInteraction)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist new interaction", e)
            }
        }
        evaluateRisk(interactionId)

        // ── Async identity resolution for calls ────────────────────────────
        if (event.type == EventType.INCOMING_CALL) {
            val phoneNumber = event.identity
            if (!phoneNumber.isNullOrEmpty()) {
                Log.d("TrustMeshIdentity", "localContactIdentityResolverExecuting=true")
                scope.launch {
                    try {
                        val resolvedCaller = identityResolver?.resolve(phoneNumber)
                        if (resolvedCaller != null) {
                            var updated: Interaction? = null
                            synchronized(lock) {
                                val currentList = _interactions.value
                                val index = currentList.indexOfFirst { it.id == interactionId }
                                if (index != -1) {
                                    val existing = currentList[index]
                                    val newTitle = resolvedCaller.identity.displayName ?: existing.title
                                    val tempUpdated = existing.copy(
                                        callerIdentity = resolvedCaller.identity,
                                        callerReputation = resolvedCaller.reputation,
                                        title = newTitle
                                    )
                                    val newList = currentList.toMutableList()
                                    newList[index] = tempUpdated
                                    _interactions.value = newList
                                    updated = tempUpdated
                                }
                            }

                            if (updated != null) {
                                val item = updated!!
                                Log.d("TrustMeshIdentity", "identityUpdate=true")
                                Log.d("TrustMeshIdentity", "overlayIdentity=${item.callerIdentity?.displayName ?: "Unknown Caller"}")
                                try {
                                    repository?.insertInteraction(item)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to persist identity-updated interaction", e)
                                }
                                evaluateRisk(interactionId)
                                Log.d(TAG, "Identity resolved for interaction $interactionId")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Identity resolution failed for interaction $interactionId", e)
                    }
                }
            }
        }
    }

    private fun evaluateRisk(interactionId: String) {
        scope.launch {
            try {
                var interaction: Interaction? = null
                synchronized(lock) {
                    val currentList = _interactions.value
                    val index = currentList.indexOfFirst { it.id == interactionId }
                    if (index != -1) {
                        interaction = currentList[index]
                    }
                }

                if (interaction == null) return@launch

                val recentEvents = try {
                    repository?.getRecentEvents(RiskEngineConfig.RELATED_EVENT_WINDOW_MS) ?: emptyList()
                } catch (e: Exception) {
                    Log.e(TAG, "getRecentEvents failed — using empty list for risk eval", e)
                    emptyList()
                }

                val assessment = RiskEngine.evaluate(interaction!!, recentEvents)

                var successfullyUpdated = false
                var updated: Interaction? = null
                synchronized(lock) {
                    val latestList = _interactions.value.toMutableList()
                    val latestIndex = latestList.indexOfFirst { it.id == interactionId }
                    if (latestIndex != -1) {
                        val currentInteraction = latestList[latestIndex]
                        val merged = currentInteraction.copy(
                            riskLevel = assessment.riskLevel,
                            riskAssessment = assessment
                        )
                        latestList[latestIndex] = merged
                        _interactions.value = latestList
                        updated = merged
                        successfullyUpdated = true
                    }
                }

                if (successfullyUpdated && updated != null) {
                    try {
                        repository?.insertInteraction(updated!!)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to persist risk-updated interaction", e)
                    }
                    SecurityIncidentManager.processInteraction(updated!!)
                    Log.d(TAG, "Risk evaluated for $interactionId — level=${assessment.riskLevel}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "evaluateRisk failed for $interactionId", e)
            }
        }
    }

    fun clearForTesting() {
        synchronized(lock) {
            _interactions.value = emptyList()
        }
        initialized = false   // Allow re-init in next test
        scope.launch {
            try {
                repository?.clearForTesting()
            } catch (e: Exception) {
                Log.e(TAG, "clearForTesting failed", e)
            }
        }
    }
}

