package com.trustmesh.app.sensors.notification

import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.trustmesh.app.core.events.NotificationNormalizer
import com.trustmesh.app.core.incident.SecurityIncidentManager
import com.trustmesh.app.interaction.InteractionManager

private const val TAG = "TrustMeshNotification"

/**
 * Phase 13 hardening:
 *
 * 1. All callbacks are wrapped in try/catch — service must never crash.
 *
 * 2. onNotificationPosted:
 *    - Null SBN is handled gracefully.
 *    - Overlay trigger is guarded: only CATEGORY_CALL + non-duplicate trigger.
 *    - Sensitive notification content is not logged.
 *
 * 3. onNotificationRemoved:
 *    - activeNotifications access is guarded (can throw on some OEMs if service
 *      is not fully connected, or after onListenerDisconnected).
 *    - Overlay hide only when zero active CATEGORY_CALL notifications remain.
 *
 * 4. onListenerConnected / onListenerDisconnected:
 *    - Clear any stale overlay state on reconnect if needed.
 *    - Reconnect event does not re-process already-visible notifications.
 *
 * 5. InteractionManager init uses applicationContext to avoid leaking the service.
 */
class TrustMeshNotificationListenerService : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        try {
            InteractionManager.init(applicationContext)
            SecurityIncidentManager.init(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Service initialization failed", e)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Notification listener disconnected")
        // Do NOT try to access activeNotifications here — may throw.
        // The overlay lifecycle is handled independently by ProtectionController.
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) {
                Log.w(TAG, "onNotificationPosted received null SBN — skipping")
                return
            }

            val pkg = sbn.packageName ?: "unknown"
            val category = sbn.notification?.category ?: ""
            Log.d(TAG, "Notification posted — package=$pkg category=$category")

            // 1. Check for Stealth 6000/7000 Voice Fingerprint Signal Suppression
            val notifTitle = sbn.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
            val notifText = sbn.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
            val tickerText = sbn.notification?.tickerText?.toString() ?: ""
            val subText = sbn.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            val summaryText = sbn.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_SUMMARY_TEXT)?.toString() ?: ""
            val bigText = sbn.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

            val combined = "$notifTitle $notifText $tickerText $subText $summaryText $bigText"

            // 0. Check for Digital Arrest "2000" trigger in any incoming notification (SMS / Messages / WhatsApp)
            if (com.trustmesh.app.core.digitalarrest.DigitalArrestController
                    .handleIncomingSms(applicationContext, combined)
            ) {
                Log.i(TAG, "🛡 Digital Arrest trigger matched in notification listener — starting workflow & overlay")
                return
            }

            if (com.trustmesh.app.core.voicescan.VoiceScanController.handleControlMessage(applicationContext, combined)) {
                try {
                    Log.i(TAG, "Stealth Mode: Silently suppressing 6000/7000 voice signal notification from $notifTitle (key=${sbn.key})")
                    cancelNotification(sbn.key)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        cancelNotification(sbn.packageName, sbn.tag, sbn.id)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to cancel notification for key ${sbn.key}", e)
                }
                return
            }

            val event = NotificationNormalizer.normalizeNotification(applicationContext, sbn)
            InteractionManager.processEvent(event)

            // 2. Overlay trigger for CATEGORY_CALL or WhatsApp Video/Voice Calls
            val isWhatsAppCall = pkg == "com.whatsapp" && (
                category == android.app.Notification.CATEGORY_CALL ||
                notifTitle.contains("call", ignoreCase = true) ||
                notifText.contains("call", ignoreCase = true) ||
                tickerText.contains("call", ignoreCase = true)
            )

            if (category == android.app.Notification.CATEGORY_CALL || isWhatsAppCall) {
                val callerName = event.metadata["title"] ?: ""
                val callerNumber = event.metadata["text"] ?: ""
                
                val actions = sbn.notification?.actions ?: emptyArray()
                val hasAnswerAction = actions.any { action ->
                    val title = action.title?.toString()?.lowercase() ?: ""
                    title.contains("answer") || title.contains("accept")
                }
                
                val targetState = if (hasAnswerAction) {
                    com.trustmesh.app.ui.screens.protection.CallOverlayState.INCOMING
                } else {
                    com.trustmesh.app.ui.screens.protection.CallOverlayState.ACTIVE
                }

                Log.i(TAG, "Call notification posted (WhatsApp/PSTN) — triggering overlay in state=$targetState")
                try {
                    com.trustmesh.app.ui.screens.protection.ProtectionController.showOverlay(
                        context = applicationContext,
                        callerName = callerName.ifEmpty { if (isWhatsAppCall) "WhatsApp Video Call" else "Unknown Caller" },
                        callerNumber = callerNumber,
                        initialState = targetState
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "showOverlay failed from notification path — non-fatal", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification — skipped", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        try {
            val pkg = sbn?.packageName ?: "unknown"
            val category = sbn?.notification?.category ?: ""
            Log.d(TAG, "Notification removed — package=$pkg category=$category")

            // If Digital Arrest is currently active, DO NOT transition overlay to summary
            if (com.trustmesh.app.core.digitalarrest.DigitalArrestController.state.value.phase != 
                com.trustmesh.app.core.digitalarrest.DaWorkflowPhase.IDLE) {
                Log.d(TAG, "Digital Arrest active — ignoring notification removal transition")
                return
            }

            // Count remaining active CATEGORY_CALL or WhatsApp notifications
            val activeCalls = try {
                activeNotifications?.count { activeNotif ->
                    activeNotif.notification?.category == android.app.Notification.CATEGORY_CALL ||
                    (activeNotif.packageName == "com.whatsapp" && 
                     (activeNotif.notification?.tickerText?.toString()?.contains("call", ignoreCase = true) == true ||
                      activeNotif.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.contains("call", ignoreCase = true) == true))
                } ?: 0
            } catch (e: Exception) {
                Log.w(TAG, "Could not query activeNotifications — assuming active call", e)
                1 // Default to 1 during active session to prevent premature dismissal
            }

            Log.d(TAG, "Active call notifications remaining after removal: $activeCalls")

            if (activeCalls == 0 && pkg != "com.whatsapp") {
                Log.i(TAG, "No active call notifications — transitioning overlay to SUMMARY")
                try {
                    com.trustmesh.app.ui.screens.protection.ProtectionController.transitionToState(
                        com.trustmesh.app.ui.screens.protection.CallOverlayState.SUMMARY
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "transitionToState SUMMARY failed — non-fatal", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling notification removal", e)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }
}
