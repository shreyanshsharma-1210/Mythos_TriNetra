package com.trustmesh.app

import com.trustmesh.app.vcd.VcdApp
import com.trustmesh.app.vcd.service.Notifications

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
    // VcdApp.onCreate() already calls Notifications.ensureChannels(this).
    // No additional override is needed; the parent covers VCD initialisation.
}
