package com.trustmesh.app.sensors.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.trustmesh.app.core.events.EventType
import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.core.events.SecurityEvent
import com.trustmesh.app.core.events.EventSource
import com.trustmesh.app.interaction.InteractionManager
import java.util.UUID

class PackageEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_PACKAGE_ADDED) return
        
        val uri = intent.data ?: return
        val packageName = uri.schemeSpecificPart ?: return
        
        Log.i("TrustMeshPackage", "Package added: $packageName")
        
        val event = SecurityEvent(
            id = UUID.randomUUID().toString(),
            type = EventType.SYSTEM_EVENT,
            source = EventSource.SYSTEM,
            timestamp = System.currentTimeMillis(),
            identity = "System",
            metadata = mapOf(
                "action" to "PACKAGE_ADDED",
                "packageName" to packageName
            ),
            initialRisk = RiskLevel.LOW
        )
        
        InteractionManager.processEvent(event)
    }
}
