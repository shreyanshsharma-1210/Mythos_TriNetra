package com.trustmesh.app.interaction

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.trustmesh.app.core.events.EventType
import com.trustmesh.app.core.events.RiskLevel
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

    private val _contacts = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val contacts: StateFlow<List<Pair<String, String>>> = _contacts.asStateFlow()

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

                // Load real call logs and contacts immediately if permissions are granted
                loadRealCallLogs(appContext)
                loadRealContacts(appContext)
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
        if (event.type == EventType.INCOMING_CALL || event.type == EventType.OUTGOING_CALL) {
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

    fun updateProtectionOutcome(interactionId: String, decision: String, isBlocked: Boolean, incidentType: com.trustmesh.app.core.incident.IncidentType? = null) {
        scope.launch {
            var updated: Interaction? = null
            synchronized(lock) {
                val currentList = _interactions.value
                val index = currentList.indexOfFirst { it.id == interactionId }
                if (index != -1) {
                    val existing = currentList[index]
                    updated = existing.copy(
                        protectionDecision = decision,
                        incidentType = incidentType ?: existing.incidentType,
                        isBlocked = isBlocked
                    )
                    val newList = currentList.toMutableList()
                    newList[index] = updated!!
                    _interactions.value = newList
                }
            }
            if (updated != null) {
                try {
                    repository?.insertInteraction(updated!!)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist interaction outcome", e)
                }
            }
        }
    }

    fun clearForTesting() {
        synchronized(lock) {
            _interactions.value = emptyList()
        }
        _contacts.value = emptyList()
        initialized = false   // Allow re-init in next test
        scope.launch {
            try {
                repository?.clearForTesting()
            } catch (e: Exception) {
                Log.e(TAG, "clearForTesting failed", e)
            }
        }
    }

    fun loadRealCallLogs(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "READ_CALL_LOG permission not granted — cannot load real call logs")
            return
        }

        scope.launch {
            try {
                val resolvedCalls = mutableListOf<Interaction>()
                val contentResolver = context.contentResolver
                
                val projection = arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.CACHED_NAME
                )
                
                val cursor = contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC LIMIT 50"
                )

                cursor?.use { c ->
                    val numberIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                    val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                    val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                    val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)

                    while (c.moveToNext()) {
                        val number = if (numberIdx >= 0) c.getString(numberIdx) else "Unknown Caller"
                        val dateMs = if (dateIdx >= 0) c.getLong(dateIdx) else System.currentTimeMillis()
                        val type = if (typeIdx >= 0) c.getInt(typeIdx) else CallLog.Calls.INCOMING_TYPE
                        val cachedName = if (nameIdx >= 0) c.getString(nameIdx) else null

                        val typeStr = when (type) {
                            CallLog.Calls.INCOMING_TYPE -> "Incoming call"
                            CallLog.Calls.OUTGOING_TYPE -> "Outgoing call"
                            CallLog.Calls.MISSED_TYPE -> "Missed call"
                            CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
                            CallLog.Calls.REJECTED_TYPE -> "Rejected call"
                            CallLog.Calls.BLOCKED_TYPE -> "Blocked call"
                            else -> "Call"
                        }

                        val timeString = timeFormat.format(Date(dateMs))
                        val id = "calllog_${dateMs}_${number.hashCode()}"
                        
                        val identity = if (!cachedName.isNullOrBlank()) {
                            com.trustmesh.app.core.identity.CallerIdentity(
                                phoneNumber = number,
                                displayName = cachedName,
                                identityType = com.trustmesh.app.core.identity.IdentityType.PERSON,
                                confidence = com.trustmesh.app.core.identity.Confidence.HIGH,
                                source = com.trustmesh.app.core.identity.IdentitySource.LOCAL_CONTACT,
                                isKnown = true
                            )
                        } else {
                            com.trustmesh.app.core.identity.CallerIdentity(
                                phoneNumber = number,
                                displayName = null,
                                identityType = com.trustmesh.app.core.identity.IdentityType.UNKNOWN,
                                confidence = com.trustmesh.app.core.identity.Confidence.NONE,
                                source = com.trustmesh.app.core.identity.IdentitySource.UNKNOWN,
                                isKnown = false
                            )
                        }

                        val isKnown = identity.isKnown
                        val riskLevel = if (isKnown) RiskLevel.LOW else RiskLevel.ELEVATED

                        val interaction = Interaction(
                            id = id,
                            title = cachedName ?: number,
                            timestamp = timeString,
                            timestampMs = dateMs,
                            riskLevel = riskLevel,
                            summary = "$typeStr observed in device logs",
                            evidence = listOf(typeStr, if (isKnown) "Known contact" else "Unknown caller"),
                            timeline = listOf(
                                "$timeString - $typeStr detected",
                                "$timeString - Logged in device history"
                            ),
                            appName = "Phone",
                            callerIdentity = identity
                        )
                        resolvedCalls.add(interaction)
                    }
                }

                synchronized(lock) {
                    val currentList = _interactions.value.toMutableList()
                    val filteredNewCalls = resolvedCalls.filter { newCall ->
                        currentList.none { existing -> 
                            existing.id == newCall.id || 
                            (existing.appName == "Phone" && Math.abs(existing.timestampMs - newCall.timestampMs) < 2000)
                        }
                    }

                    if (filteredNewCalls.isNotEmpty()) {
                        val merged = (currentList + filteredNewCalls).sortedByDescending { it.timestampMs }
                        _interactions.value = merged
                        Log.i(TAG, "Merged ${filteredNewCalls.size} real calls from device call log")
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException: READ_CALL_LOG permission not granted or revoked dynamically", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load real call logs from device", e)
            }
        }
    }

    fun loadRealContacts(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "READ_CONTACTS permission not granted — cannot load real contacts")
            return
        }

        scope.launch {
            try {
                val resolvedContacts = mutableListOf<Pair<String, String>>()
                val contentResolver = context.contentResolver
                
                val projection = arrayOf(
                    ContactsContract.Contacts.DISPLAY_NAME,
                    ContactsContract.Contacts.HAS_PHONE_NUMBER
                )
                
                val cursor = contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
                )

                cursor?.use { c ->
                    val nameIdx = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                    val hasPhoneIdx = c.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)

                    while (c.moveToNext()) {
                        val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                        val hasPhone = if (hasPhoneIdx >= 0) c.getInt(hasPhoneIdx) else 0

                        if (!name.isNullOrBlank() && hasPhone > 0) {
                            resolvedContacts.add(Pair(name, "Safe contact · Verified locally"))
                        }
                    }
                }

                _contacts.value = resolvedContacts
                Log.i(TAG, "Loaded ${resolvedContacts.size} real contacts from device")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException: READ_CONTACTS permission not granted or revoked dynamically", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load real contacts from device", e)
            }
        }
    }
}

