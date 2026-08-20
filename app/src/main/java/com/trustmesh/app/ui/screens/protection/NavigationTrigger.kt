package com.trustmesh.app.ui.screens.protection

import kotlinx.coroutines.flow.MutableSharedFlow

object NavigationTrigger {
    val navigationEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)

    fun triggerNavigation(route: String) {
        navigationEvents.tryEmit(route)
    }
}
