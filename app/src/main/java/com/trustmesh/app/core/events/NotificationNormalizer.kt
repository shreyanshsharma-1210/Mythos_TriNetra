package com.trustmesh.app.core.events

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification

object NotificationNormalizer {
    fun normalizeNotification(context: Context, sbn: StatusBarNotification?): SecurityEvent {
        val packageName = sbn?.packageName ?: "Unknown app"
        val timestamp = sbn?.postTime ?: System.currentTimeMillis()
        val key = sbn?.key ?: ""
        
        var appName = packageName
        try {
            val packageManager = context.packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            appName = packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            // Fallback to packageName if not found
        }
        
        val notification = sbn?.notification
        val title = notification?.extras?.getString(Notification.EXTRA_TITLE) ?: ""
        val text = notification?.extras?.getString(Notification.EXTRA_TEXT) ?: ""
        val category = notification?.category ?: ""
        
        val metadata = mutableMapOf<String, String>()
        metadata["packageName"] = packageName
        metadata["appName"] = appName
        metadata["notificationKey"] = key
        metadata["title"] = title
        metadata["text"] = text
        metadata["category"] = category

        return SecurityEvent(
            type = EventType.NOTIFICATION_POSTED,
            source = EventSource.NOTIFICATION_LISTENER_SERVICE,
            timestamp = timestamp,
            identity = appName,
            metadata = metadata,
            initialRisk = RiskLevel.LOW
        )
    }
}
