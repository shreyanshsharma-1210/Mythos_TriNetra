package com.trustmesh.app

import android.util.Log
import com.trustmesh.app.vcd.VcdApp
import com.trustmesh.app.vcd.service.AvailabilityService
import com.trustmesh.app.vcd.voip.CallManager

/**
 * Merged Application class for TriNetra + Voice Clone Defence module.
 *
 * Extends [VcdApp] so that the VCD composables can safely cast
 * `LocalContext.current.applicationContext as VcdApp` — the cast succeeds
 * because TriNetraApplication IS-A VcdApp.
 *
 * VcdApp itself extends Android's [android.app.Application] and initialises
 * the Room database, ModelRuntime, ContactRepository, and notification channels.
 * Everything the VCD module needs is already provided by the parent.
 *
 * Trinetra-specific singletons (InteractionManager, SecurityIncidentManager) are
 * still initialised in [MainActivity.onCreate] — nothing here changes that flow.
 */
class TriNetraApplication : VcdApp() {
    override fun onCreate() {
        super.onCreate() // VcdApp.onCreate() initialises Room, ModelRuntime and notification channels.

        // Be reachable for calls by default, so a device can be rung without the user first flipping
        // a "make me discoverable" switch — a phone you can only call while its owner is staring at
        // a toggle is not a phone. Advertising over mDNS and listening on the signalling socket need
        // no microphone; RECORD_AUDIO is requested only when a call is actually answered.
        //
        // A foreground service carries this so reachability survives the process being backgrounded
        // or trimmed. If it cannot be promoted (e.g. cold-started in the background on Android 12+),
        // fall back to plain goAvailable so the device is at least reachable while this process lives.
        AvailabilityService.start(this)
        runCatching { CallManager.goAvailable(this, contactId = null) }
            .onFailure { Log.w(TAG, "could not start call availability at launch", it) }
    }

    private companion object {
        const val TAG = "TriNetraApplication"
    }
}
