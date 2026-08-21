package com.trustmesh.app.sensors.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.trustmesh.app.ui.screens.protection.CallOverlayState
import com.trustmesh.app.ui.screens.protection.ProtectionController

class TrustMeshPhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            Log.i("TrustMeshPhoneState", "Phone state changed to $state")
            
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    // We typically leave this to CallScreeningService for incoming calls
                    // as it has richer data to start the overlay with.
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    // Call picked up (incoming) or dialing/active (outgoing)
                    // Transition the overlay to the small ACTIVE pill
                    ProtectionController.transitionToState(CallOverlayState.ACTIVE)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    // Call ended
                    // Transition to SUMMARY, which will auto-dismiss after delay
                    ProtectionController.transitionToState(CallOverlayState.SUMMARY)
                }
            }
        }
    }
}
