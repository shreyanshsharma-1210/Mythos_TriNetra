package com.trustmesh.app.core.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TrustMeshSettings {
    private val _isExternalLookupEnabled = MutableStateFlow(false)
    val isExternalLookupEnabled: StateFlow<Boolean> = _isExternalLookupEnabled.asStateFlow()

    fun setExternalLookupEnabled(enabled: Boolean) {
        _isExternalLookupEnabled.value = enabled
    }
}
