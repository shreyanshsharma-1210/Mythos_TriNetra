package com.trustmesh.app.sensors.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.trustmesh.app.core.events.EventType
import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.core.events.SecurityEvent
import com.trustmesh.app.core.events.EventSource
import com.trustmesh.app.interaction.InteractionManager

private const val TAG = "TrustMeshSmsReceiver"

class TrustMeshSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        
        try {
            InteractionManager.init(context.applicationContext)
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages == null || messages.isEmpty()) return
            
            // Reassemble long SMS
            val messageBody = StringBuilder()
            var sender = ""
            var timestamp = System.currentTimeMillis()
            
            for (sms in messages) {
                if (sms != null) {
                    messageBody.append(sms.messageBody)
                    sender = sms.displayOriginatingAddress ?: sender
                    timestamp = sms.timestampMillis
                }
            }
            
            val fullText = messageBody.toString()
            Log.d(TAG, "SMS received from $sender — length: ${fullText.length}")

            // Voice-analysis control codes are handled before anything else and unconditionally.
            // They must not depend on the interaction pipeline having caught up, on an overlay
            // being on screen, or on the risk engine having produced a score yet — the run has to
            // start the moment the message lands, mid-call.
            //
            // The message is then dropped rather than processed as an event. It is a control
            // message about the call, not a threat in its own right: fed to the pipeline it becomes
            // a bare-number SMS, gets classified as OTP theft, and that classification then takes
            // over the call overlay — which is how a clone-voice warning ended up reading as an OTP
            // scam alert.
            if (com.trustmesh.app.core.voicescan.VoiceScanController.handleControlMessage(context.applicationContext, fullText)) {
                Log.i(TAG, "Voice-analysis control code processed from $sender — not routed to the threat pipeline")
                return
            }

            val metadata = mutableMapOf<String, String>()
            metadata["packageName"] = "com.google.android.apps.messaging"
            metadata["appName"] = "Messages (Direct SMS)"
            metadata["notificationKey"] = "sms_${timestamp}_${sender.hashCode()}"
            metadata["title"] = sender
            metadata["text"] = fullText
            metadata["category"] = "msg"
            
            val event = SecurityEvent(
                type = EventType.NOTIFICATION_POSTED,
                source = EventSource.NOTIFICATION_LISTENER_SERVICE, // Treat as notification for AttackContextEngine
                timestamp = timestamp,
                identity = "Messages (Direct SMS)",
                metadata = metadata,
                initialRisk = RiskLevel.LOW
            )
            
            InteractionManager.processEvent(event)

            // 🚨 Emergency Alert Trigger: Check if message contains "TriNetra" keyword
            if (fullText.contains("TriNetra", ignoreCase = true)) {
                Log.i(TAG, "🚨 TRINETRA EMERGENCY KEYWORD DETECTED in SMS from $sender! Triggering emergency alarm, vibration & overlay box.")

                // 1. Trigger Alarm & Vibration API
                com.trustmesh.app.core.alert.EmergencyAlarmManager.startEmergencyAlarm(context.applicationContext)

                // 2. Launch High-Priority Emergency Activity overlay (wakes screen, shows when locked)
                com.trustmesh.app.ui.screens.alert.EmergencyAlertActivity.launch(context.applicationContext, sender, fullText)

                // 3. Render System Window Overlay if SYSTEM_ALERT_WINDOW permission granted
                com.trustmesh.app.ui.screens.alert.EmergencyAlertOverlayManager.showOverlay(context.applicationContext, sender, fullText)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process incoming SMS", e)
        }
    }
}
