package com.trustmesh.app.core.events

import java.util.UUID

enum class EventType {
    INCOMING_CALL,
    OUTGOING_CALL,
    NOTIFICATION_POSTED,
    SYSTEM_EVENT,
    UNKNOWN
}

enum class EventSource {
    CALL_SCREENING_SERVICE,
    NOTIFICATION_LISTENER_SERVICE,
    SYSTEM
}

data class SecurityEvent(
    val id: String = UUID.randomUUID().toString(),
    val type: EventType,
    val source: EventSource,
    val timestamp: Long = System.currentTimeMillis(),
    val interactionId: String? = null,
    val identity: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val initialRisk: RiskLevel = RiskLevel.LOW
)
