package com.trustmesh.app.vcd.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.trustmesh.app.vcd.VcdApp
import com.trustmesh.app.vcd.voip.CallManager

/**
 * Keeps the device reachable for calls while the app is not in the foreground.
 *
 * [CallManager] holds the listening socket and the mDNS advertisement in a process-scoped singleton.
 * On its own that means a device stops being reachable the moment Android trims the backgrounded
 * process — a phone you can only be called on while its owner is staring at the app is not a phone.
 * This foreground service gives that reachability a lifetime the OS will respect, and START_STICKY
 * brings it back if the process is killed and restarted.
 *
 * It holds no microphone and no call state. It only keeps [CallManager.goAvailable] in effect; the
 * actual call, and the microphone, are owned by [VoipCallService] once a call is answered.
 */
class AvailabilityService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Notifications.ensureChannels(this)

        // Make the device reachable first, so it works even if the foreground promotion below is
        // refused (some OEM/background conditions). goAvailable is idempotent.
        (application as? VcdApp)?.let {
            runCatching { CallManager.goAvailable(it, contactId = null) }
                .onFailure { t -> Log.w(TAG, "goAvailable failed", t) }
        }

        return try {
            ServiceCompat.startForeground(
                this,
                Notifications.NOTIFICATION_ID_AVAILABLE,
                Notifications.availabilityNotification(this),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
            START_STICKY
        } catch (t: Throwable) {
            // Could not become a foreground service (e.g. started from the background on Android 12+).
            // Reachability is already on for as long as this process lives; drop the keep-alive
            // rather than crash.
            Log.w(TAG, "could not start availability foreground service", t)
            stopSelf()
            START_NOT_STICKY
        }
    }

    companion object {
        private const val TAG = "AvailabilityService"

        /** Best-effort: keeps the device reachable in the background. Never throws to the caller. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AvailabilityService::class.java),
                )
            }.onFailure { Log.w(TAG, "startForegroundService refused", it) }
        }
    }
}
