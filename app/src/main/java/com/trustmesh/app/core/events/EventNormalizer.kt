package com.trustmesh.app.core.events

import android.telecom.Call

object EventNormalizer {
    fun normalizeIncomingCall(details: Call.Details?): SecurityEvent {
        val handle = details?.handle?.schemeSpecificPart ?: "Unknown"
        val timestamp = details?.creationTimeMillis ?: System.currentTimeMillis()
        val isIncoming = details?.callDirection == Call.Details.DIRECTION_INCOMING

        val hasNumber = details?.handle != null
        val maskedNumber = if (handle == "Unknown" || handle.length < 4) "Unknown" else "******" + handle.takeLast(4)
        android.util.Log.d("TrustMeshIdentity", "callDetailsProvidedNumber=$hasNumber incomingNumber=$maskedNumber")

        val metadata = mutableMapOf<String, String>()
        metadata["isIncoming"] = isIncoming.toString()
        metadata["callerNumber"] = handle
        
        val callerName = details?.callerDisplayName
        if (!callerName.isNullOrEmpty()) {
            metadata["oemDisplayName"] = callerName
        }

        return SecurityEvent(
            type = EventType.INCOMING_CALL,
            source = EventSource.CALL_SCREENING_SERVICE,
            timestamp = timestamp,
            identity = handle,
            metadata = metadata,
            initialRisk = RiskLevel.LOW
        )
    }
}
