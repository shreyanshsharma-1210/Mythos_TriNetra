package com.trustmesh.app.vcd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.trustmesh.app.MainActivity
import com.trustmesh.app.R

object Notifications {

    const val CHANNEL_CAPTURE = "vcd_capture"
    const val NOTIFICATION_ID_CAPTURE = 4201

    const val CHANNEL_CALL = "vcd_call"
    const val NOTIFICATION_ID_CALL = 4202

    const val CHANNEL_INCOMING = "vcd_incoming"
    const val NOTIFICATION_ID_INCOMING = 4203

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_CAPTURE,
            context.getString(R.string.notif_channel_capture_name),
            // LOW keeps it silent but persistent. This notification is a disclosure surface, not
            // an alert, and it must never be dismissible or hidden while the mic is open.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_capture_desc)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)

        // IMPORTANCE_HIGH plus a full-screen intent is what makes an incoming call take over the
        // screen the way a call should, instead of sliding in as a banner. The channel carries no
        // sound of its own: Ringer plays the user's actual ringtone, so a TRINETRA call rings with
        // the sound they already associate with being called.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INCOMING,
                context.getString(R.string.notif_channel_incoming_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_incoming_desc)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALL,
                context.getString(R.string.notif_channel_call_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_call_desc)
                setShowBadge(true)
            }
        )
    }

    /**
     * The incoming-call notification.
     *
     * Uses a full-screen intent so it takes over the display, including over the lock screen, which
     * is the behaviour that separates a call from a notification. MainActivity is declared
     * showWhenLocked and turnScreenOn so the call screen is what the user actually sees.
     */
    fun incomingCallNotification(context: Context, caller: String?): Notification {
        val fullScreen = PendingIntent.getActivity(
            context,
            4,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_SHOW_CALL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val decline = PendingIntent.getService(
            context,
            5,
            Intent(context, VoipCallService::class.java).setAction(VoipCallService.ACTION_DECLINE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val answer = PendingIntent.getService(
            context,
            6,
            Intent(context, VoipCallService::class.java).setAction(VoipCallService.ACTION_ANSWER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(caller ?: "Unknown caller")
            .setContentText("Incoming TRINETRA call")
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, "Decline", decline)
            .addAction(0, "Answer", answer)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /** Action MainActivity looks for to jump straight to the call screen. */
    const val ACTION_SHOW_CALL = "com.mythos.vcd.action.SHOW_CALL"

    /**
     * The ongoing-call notification.
     *
     * Serves the same disclosure purpose as the capture one: a VoIP call holds the microphone, and
     * the user is entitled to a persistent, non-dismissible reminder of that plus a one-tap way to
     * end it, without hunting for the app.
     */
    fun callNotification(context: Context, peer: String?): Notification {
        val open = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangUp = PendingIntent.getService(
            context,
            3,
            Intent(context, VoipCallService::class.java).setAction(VoipCallService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = peer?.let { "Connected to $it. The microphone is open." }
            ?: "The microphone is open for this call."

        return NotificationCompat.Builder(context, CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("TRINETRA call in progress")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, "End call", hangUp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    fun captureNotification(context: Context, contactName: String?): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, LiveVerificationService::class.java)
                .setAction(LiveVerificationService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = if (contactName != null) {
            context.getString(R.string.notif_capture_text) + " Checking against $contactName."
        } else {
            context.getString(R.string.notif_capture_text)
        }

        return NotificationCompat.Builder(context, CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(context.getString(R.string.notif_capture_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
}
