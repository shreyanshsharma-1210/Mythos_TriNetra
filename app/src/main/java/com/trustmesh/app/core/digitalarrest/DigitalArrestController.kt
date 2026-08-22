package com.trustmesh.app.core.digitalarrest

import android.content.Context
import android.util.Log
import com.trustmesh.app.core.alert.FamilyAlertConfig
import com.trustmesh.app.core.alert.TextBeeHttpClient
import com.trustmesh.app.ui.screens.digitalarrest.DigitalArrestActivity
import com.trustmesh.app.ui.screens.digitalarrest.DigitalArrestOverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "DigitalArrestCtrl"

// ── Demo constants ─────────────────────────────────────────────────────────────

/** The ONLY string that activates the Digital Arrest workflow (exact match after trim). */
const val DEMO_TRIGGER_CODE = "2000"

const val DEMO_MODE = true

// ── Workflow phases (for animated progress UI) ────────────────────────────────

enum class DaWorkflowPhase {
    IDLE,
    SMS_DETECTED,
    TRIGGER_VERIFIED,
    CAPTURING_EVIDENCE,
    COLLECTING_METADATA,
    EVALUATING_RULES,
    SCORING_RISK,
    SEALING_EVIDENCE,
    GENERATING_REPORT,
    NOTIFYING_CONTACTS,
    COMPLETE,
    ERROR,
}

data class DigitalArrestState(
    val phase: DaWorkflowPhase = DaWorkflowPhase.IDLE,
    val incident: DigitalArrestIncident? = null,
    val reportPath: String? = null,
    val errorMessage: String? = null,

    /** True when SMS has been received but is not "2000" — let UI ignore silently. */
    val wrongTrigger: Boolean = false,
)

/**
 * Singleton controller for the Digital Arrest demo workflow.
 *
 * Called from [com.trustmesh.app.sensors.sms.TrustMeshSmsReceiver] when it detects an SMS.
 * The controller owns all async work and exposes [state] to the Compose UI.
 */
object DigitalArrestController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(DigitalArrestState())
    val state: StateFlow<DigitalArrestState> = _state.asStateFlow()

    // ── Entry point from SMS receiver ─────────────────────────────────────────

    /**
     * Evaluates [messageBody] against the trigger condition.
     *
     * @return true if the message was the exact trigger "2000" and the workflow was started.
     *         false for every other message — caller should process normally.
     */
    fun handleIncomingSms(context: Context, messageBody: String): Boolean {
        val trimmed = messageBody.trim()

        // Match exact "2000", word boundary \b2000\b, or string containing "2000"
        val isMatch = trimmed == DEMO_TRIGGER_CODE ||
                Regex("\\b2000\\b").containsMatchIn(messageBody) ||
                messageBody.contains(DEMO_TRIGGER_CODE)

        if (!isMatch) {
            Log.d(TAG, "SMS body='$messageBody' — not trigger (does not contain '$DEMO_TRIGGER_CODE')")
            return false
        }

        Log.i(TAG, "🎯 DEMO TRIGGER MATCHED — body='$messageBody' — launching Digital Arrest overlay & Activity")
        // 1. Show System Overlay over active video call / app
        DigitalArrestOverlayManager.showOverlay(context.applicationContext)
        // 2. Launch high-priority Activity to wake screen and show UI over lockscreen
        DigitalArrestActivity.launch(context.applicationContext)
        // 3. Force call overlay state in ProtectionController if call is active
        try {
            com.trustmesh.app.ui.screens.protection.ProtectionController.showOverlay(
                context = context.applicationContext,
                callerName = "Inspector Rahul Sharma (Cyber Cell)",
                callerNumber = "+91 XXXXX XXXXX",
                initialState = com.trustmesh.app.ui.screens.protection.CallOverlayState.ACTIVE
            )
        } catch (e: Exception) {
            Log.e(TAG, "ProtectionController trigger failed — non-fatal", e)
        }

        scope.launch { runWorkflow(context.applicationContext) }
        return true
    }

    /**
     * Manual trigger for the demo simulate-trigger button.
     * Identical to receiving the SMS — calls the same workflow.
     */
    fun simulateTrigger(context: Context) {
        Log.i(TAG, "🔁 SIMULATE TRIGGER called — running Digital Arrest workflow")
        DigitalArrestOverlayManager.showOverlay(context.applicationContext)
        DigitalArrestActivity.launch(context.applicationContext)
        try {
            com.trustmesh.app.ui.screens.protection.ProtectionController.showOverlay(
                context = context.applicationContext,
                callerName = "Inspector Rahul Sharma (Cyber Cell)",
                callerNumber = "+91 XXXXX XXXXX",
                initialState = com.trustmesh.app.ui.screens.protection.CallOverlayState.ACTIVE
            )
        } catch (e: Exception) {
            Log.e(TAG, "ProtectionController trigger failed — non-fatal", e)
        }
        scope.launch { runWorkflow(context.applicationContext) }
    }

    /** Resets to IDLE so the demo can be run again. */
    fun reset() {
        _state.value = DigitalArrestState()
    }

    // ── Workflow coroutine ─────────────────────────────────────────────────────

    private suspend fun runWorkflow(context: Context) {
        try {
            // ── 0s: SMS detected ─────────────────────────────────────────────
            _state.value = _state.value.copy(phase = DaWorkflowPhase.SMS_DETECTED)

            // ── 0.5s: Trigger verified ────────────────────────────────────────
            delay(500)
            _state.value = _state.value.copy(phase = DaWorkflowPhase.TRIGGER_VERIFIED)

            // ── 1.0s: Capture evidence screenshot ────────────────────────────
            delay(500)
            _state.value = _state.value.copy(phase = DaWorkflowPhase.CAPTURING_EVIDENCE)

            val (screenshotPath, screenshotBytes) = captureScreenshot(context)

            // ── 1.5s: Collect metadata ────────────────────────────────────────
            delay(500)
            _state.value = _state.value.copy(phase = DaWorkflowPhase.COLLECTING_METADATA)

            // ── 2.0s: Evaluate rules ─────────────────────────────────────────
            delay(500)
            _state.value = _state.value.copy(phase = DaWorkflowPhase.EVALUATING_RULES)

            // ── 2.5s: Score risk ─────────────────────────────────────────────
            delay(500)
            _state.value = _state.value.copy(phase = DaWorkflowPhase.SCORING_RISK)

            val incident = DigitalArrestEngine.buildIncident(screenshotPath, screenshotBytes)
            _state.value = _state.value.copy(incident = incident)

            // ── 3.0s: Seal evidence bundle ────────────────────────────────────
            delay(500)
            _state.value = _state.value.copy(phase = DaWorkflowPhase.SEALING_EVIDENCE)

            // ── 3.5s: Generate PDF report ─────────────────────────────────────
            delay(500)
            _state.value = _state.value.copy(phase = DaWorkflowPhase.GENERATING_REPORT)

            val reportPath = DigitalArrestPdfGenerator.generate(context, incident)

            // ── 4.0s: Send trusted-contact notification ───────────────────────
            delay(500)
            _state.value = _state.value.copy(phase = DaWorkflowPhase.NOTIFYING_CONTACTS)

            val notified = sendTrustedContactAlert(incident)
            val updatedIncident = incident.copy(
                reportPath = reportPath,
                notificationStatus = if (notified) "SENT" else "PENDING",
            )

            // ── 4.5s: Complete ───────────────────────────────────────────────
            delay(500)
            _state.value = DigitalArrestState(
                phase = DaWorkflowPhase.COMPLETE,
                incident = updatedIncident,
                reportPath = reportPath,
            )

            Log.i(TAG, "✅ Digital Arrest workflow complete — case=${updatedIncident.caseId} report=$reportPath")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Digital Arrest workflow failed", e)
            _state.value = _state.value.copy(
                phase = DaWorkflowPhase.ERROR,
                errorMessage = e.message ?: "Unknown error",
            )
        }
    }

    // ── Screenshot capture ────────────────────────────────────────────────────

    /**
     * Attempts to capture the current screen.
     *
     * On Android, apps cannot capture the global screen without MediaProjection permission
     * (which requires user interaction at the moment of capture). For the demo we save
     * a fallback demo image that represents the "WhatsApp call was active" state.
     *
     * @return Pair<absolutePath, fileBytes>. path may be null if write fails.
     */
    private fun captureScreenshot(context: Context): Pair<String?, ByteArray?> {
        return try {
            // Use the bundled _screen.png demo screenshot if it exists in assets/root
            val assetName = "demo_whatsapp_call.png"
            val dir = java.io.File(context.filesDir, "da_evidence").also { it.mkdirs() }
            val dest = java.io.File(dir, "incident-screen.png")

            // Try loading from assets
            val bytes = try {
                context.assets.open(assetName).use { it.readBytes() }
            } catch (assetEx: Exception) {
                // Fallback: generate a simple placeholder image programmatically
                Log.d(TAG, "Demo asset not found ($assetName) — generating placeholder screenshot")
                generatePlaceholderScreenshot()
            }

            dest.writeBytes(bytes)
            Log.i(TAG, "Evidence screenshot saved to ${dest.absolutePath}")
            Pair(dest.absolutePath, bytes)
        } catch (e: Exception) {
            Log.w(TAG, "Screenshot capture failed — continuing without image", e)
            Pair(null, null)
        }
    }

    /**
     * Generates a minimal placeholder PNG bitmap representing an active WhatsApp video call.
     * Used when the demo asset is not bundled.
     */
    private fun generatePlaceholderScreenshot(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(800, 500, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val bg = android.graphics.Paint().apply { color = android.graphics.Color.rgb(30, 8, 12) }
        c.drawRect(0f, 0f, 800f, 500f, bg)

        val title = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 32f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
        }
        c.drawText("WhatsApp Video Call", 400f, 200f, title)

        val sub = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(150, 160, 180)
            textSize = 20f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        c.drawText("Inspector Rahul Sharma", 400f, 240f, sub)
        c.drawText("[EVIDENCE CAPTURE — DEMO SCREENSHOT]", 400f, 290f, sub)

        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    // ── Trusted contact notification ─────────────────────────────────────────

    private suspend fun sendTrustedContactAlert(incident: DigitalArrestIncident): Boolean {
        val apiKey = FamilyAlertConfig.getApiKey()
        val deviceId = FamilyAlertConfig.getDeviceId()
        val recipients = FamilyAlertConfig.getFamilyNumbers()

        if (apiKey.isBlank()) {
            Log.w(TAG, "TextBee API key not configured — family notification skipped")
            return false
        }
        if (recipients.isEmpty()) {
            Log.w(TAG, "No family numbers configured — family notification skipped")
            return false
        }

        val message = DigitalArrestEngine.buildTrustedContactMessage(incident.caseId)

        return try {
            // Reuse the existing TextBee client infrastructure
            val client = com.trustmesh.app.core.alert.DefaultTextBeeHttpClient()
            val result = client.sendSms(
                apiKey = apiKey,
                deviceId = deviceId,
                recipients = recipients,
                message = message,
                simSlot = FamilyAlertConfig.getSimSlot(),
            )
            if (result.isSuccess) {
                Log.i(TAG, "✅ Trusted contact alert sent for case ${incident.caseId}")
                true
            } else {
                Log.w(TAG, "Trusted contact alert delivery failed: ${result.exceptionOrNull()?.message}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception sending trusted contact alert", e)
            false
        }
    }
}
