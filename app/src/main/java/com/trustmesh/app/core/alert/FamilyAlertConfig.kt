package com.trustmesh.app.core.alert

import com.trustmesh.app.BuildConfig

/**
 * Configuration helper for TextBee Family Alert integration.
 * Reads configuration from BuildConfig / environment / local.properties.
 *
 * Invariants:
 * - API Key is NOT hardcoded in Kotlin logic; read from BuildConfig.TEXTBEE_API_KEY.
 * - Family Alert Number is configurable via BuildConfig.FAMILY_ALERT_NUMBER.
 * - Device ID is configurable via BuildConfig.TEXTBEE_DEVICE_ID.
 */
object FamilyAlertConfig {
    const val TEXTBEE_ENDPOINT = "https://api.textbee.dev/api/v1/gateway/send-sms"
    const val HIGH_RISK_THRESHOLD = 50

    fun getApiKey(): String {
        return BuildConfig.TEXTBEE_API_KEY
    }

    fun getFamilyNumbers(): List<String> {
        val raw = BuildConfig.FAMILY_ALERT_NUMBERS
        return raw.split(",").map { it.trim().replace(" ", "") }.filter { it.isNotBlank() }
    }

    fun getFamilyNumber(): String {
        return getFamilyNumbers().firstOrNull() ?: ""
    }

    fun getDeviceId(): String {
        return BuildConfig.TEXTBEE_DEVICE_ID
    }

    fun getSimSlot(): Int {
        return BuildConfig.TEXTBEE_SIM_SLOT
    }
}
