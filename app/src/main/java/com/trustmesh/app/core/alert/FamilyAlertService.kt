package com.trustmesh.app.core.alert

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * FamilyAlertService manages asynchronous SMS notification to family members
 * when the RiskAssessment score exceeds the HIGH_RISK_THRESHOLD.
 *
 * Invariants:
 * 1. Strict trigger: riskScore > HIGH_RISK_THRESHOLD. Equal scores do NOT trigger.
 * 2. ONE SMS per call: Uses interactionId to permanently lock the key after sending.
 *    The key is NEVER removed on successful send, preventing repeats even if evaluateRisk fires multiple times.
 * 3. Non-blocking: Executes asynchronously — zero impact on call screening.
 * 4. Privacy & Security: API key is never logged. Phone numbers are masked in logs.
 */
object FamilyAlertService {
    private const val TAG = "FamilyAlertService"
    private const val GLOBAL_COOLDOWN_MS = 5 * 60 * 1000L

    // Thread-safe set of interaction/incident IDs — key is added before sending and NEVER removed on success
    private val sentAlertKeys = ConcurrentHashMap.newKeySet<String>()
    
    // Global tracker to block any alert for 5 minutes
    private var lastSentTimestamp = AtomicLong(0L)

    private var defaultHttpClient: TextBeeHttpClient = DefaultTextBeeHttpClient()

    /** For testing or custom DI injection */
    fun setHttpClientForTesting(client: TextBeeHttpClient?) {
        defaultHttpClient = client ?: DefaultTextBeeHttpClient()
    }

    /** Clears idempotency cache for unit testing */
    fun clearForTesting() {
        sentAlertKeys.clear()
        lastSentTimestamp.set(0L)
        defaultHttpClient = DefaultTextBeeHttpClient()
    }

    /** Returns set of keys that have received alerts (read-only for tests) */
    fun getSentAlertKeysForTesting(): Set<String> {
        return sentAlertKeys.toSet()
    }

    /**
     * Sends a single family alert SMS per interaction.
     * The idempotency key is permanently locked on first attempt — no duplicates.
     */
    suspend fun sendHighRiskAlert(
        riskScore: Int,
        incidentType: String? = null,
        callerName: String? = null,
        interactionId: String? = null,
        httpClient: TextBeeHttpClient = defaultHttpClient,
        overrideRecipient: String? = null
    ): Boolean {
        // 1. Strict trigger condition
        if (riskScore <= FamilyAlertConfig.HIGH_RISK_THRESHOLD) {
            Log.d(TAG, "Risk score $riskScore <= ${FamilyAlertConfig.HIGH_RISK_THRESHOLD} — family alert skipped")
            return false
        }

        // 2. ONE-SMS-PER-INTERACTION gate: lock the key BEFORE sending
        //    If key already present (or incident equivalent), we already sent (or attempted) — hard stop.
        val idempotencyKey = interactionId?.ifBlank { null }?.removePrefix("INC-")
        if (idempotencyKey != null) {
            if (!sentAlertKeys.add(idempotencyKey)) {
                Log.i(TAG, "🔒 Duplicate suppressed — already alerted for interaction: $idempotencyKey")
                return false
            }
        }
        
        // 3. Global Time-Window gate (5 minutes)
        val now = System.currentTimeMillis()
        val lastSent = lastSentTimestamp.get()
        if (now - lastSent < GLOBAL_COOLDOWN_MS) {
            Log.i(TAG, "⏳ Global cooldown suppressed — alert sent within last 5 minutes")
            // Key STAYS locked — do not unlock so future evaluations in the same call stay suppressed
            return false
        }

        // 4. Obtain configuration
        val apiKey = FamilyAlertConfig.getApiKey()
        val deviceId = FamilyAlertConfig.getDeviceId()
        val recipients = if (!overrideRecipient.isNullOrBlank()) {
            listOf(overrideRecipient)
        } else {
            FamilyAlertConfig.getFamilyNumbers()
        }
        val maskedRecipients = recipients.joinToString(", ") { maskPhoneNumber(it) }

        if (apiKey.isBlank()) {
            Log.w(TAG, "TextBee API key is missing — cannot send family SMS alert")
            // Key stays locked — better to miss one alert than to spam on every evaluation
            return false
        }

        // 5. Build Hinglish alert message
        val message = buildAlertMessage(riskScore, incidentType, callerName)

        Log.i(TAG, "📲 Sending family risk alert SMS to $maskedRecipients — score=$riskScore% key=$idempotencyKey")

        // 6. Send via TextBee API — key is ALREADY locked above, never removed on success
        return try {
            val result = httpClient.sendSms(
                apiKey = apiKey,
                deviceId = deviceId,
                recipients = recipients,
                message = message,
                simSlot = FamilyAlertConfig.getSimSlot()
            )

            if (result.isSuccess) {
                lastSentTimestamp.set(System.currentTimeMillis())
                Log.i(TAG, "✅ Family risk alert SMS sent successfully to $maskedRecipients")
                true
            } else {
                val errMessage = result.exceptionOrNull()?.message ?: "Unknown network error"
                Log.e(TAG, "❌ Family risk alert SMS delivery failed: $errMessage")
                // Key STAYS locked — delivery failure (carrier/network) should not cause spam retries
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Unhandled exception in family alert: ${e.message}", e)
            // Key STAYS locked — prevents exception loops from causing spam retries
            false
        }
    }

    /**
     * Builds Hinglish alert message with caller name and dynamic risk score.
     */
    fun buildAlertMessage(
        riskScore: Int,
        incidentType: String? = null,
        callerName: String? = null
    ): String {
        val callerPart = if (!callerName.isNullOrBlank()) " (Caller: $callerName)" else ""
        return "⚠️ TriNetra Security Alert$callerPart: Aapke family member ke phone par scam call detect hua hai. Risk Score: $riskScore%. Kripya turant unse contact karein aur OTP, PIN ya banking details share na karne ki salah dein."
    }

    /**
     * Masks phone number for safe logging (e.g. +91 9691600998 -> +91 ******0998).
     */
    fun maskPhoneNumber(number: String): String {
        if (number.length <= 4) return "****"
        val prefixLen = if (number.startsWith("+")) 3 else 2
        if (number.length <= prefixLen + 4) {
            return number.take(prefixLen) + "*".repeat(number.length - prefixLen)
        }
        val suffix = number.takeLast(4)
        val prefix = number.take(prefixLen)
        val maskedMid = "*".repeat(number.length - prefixLen - 4)
        return "$prefix$maskedMid$suffix"
    }
}
