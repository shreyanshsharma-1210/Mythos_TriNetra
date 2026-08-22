package com.trustmesh.app.core.digitalarrest

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Deterministic rule and threat engine for the Digital Arrest demo.
 *
 * All scoring is FIXED for demo reliability — not LLM-based, not fuzzy.
 * The incident report clearly labels output as SIMULATION / TRINETRA ASSESSMENT.
 */
object DigitalArrestEngine {

    // ── Demo Caller ────────────────────────────────────────────────────────────

    val DEMO_CALLER = DaCaller(
        displayName = "Inspector Rahul Sharma",
        phoneNumber = "+91 XXXXX XXXXX",
        claimedAgency = "Cyber Crime Investigation Unit",
        claimedRole = "Cyber Crime Officer",
        claimedJurisdiction = "Indore, Madhya Pradesh",
        badgeNumber = "CCIU-47291",
        identityVerification = "UNVERIFIED",
    )

    // ── Case / Document ID generation ─────────────────────────────────────────

    fun generateCaseId(): String {
        val date = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val suffix = UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
        return "TRN-DA-$date-$suffix"
    }

    fun generateDocumentId(): String {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        return "TRNDA-DOC-$suffix"
    }

    // ── Rules ─────────────────────────────────────────────────────────────────

    /**
     * Evaluates the deterministic rule set.
     *
     * RULE_05 (financial pressure) is included when [financialPressureDetected] is true.
     * All other rules are always fired in the digital-arrest scenario.
     */
    fun evaluateRules(financialPressureDetected: Boolean = true): List<DaRule> = buildList {
        add(
            DaRule(
                id = "RULE_01",
                title = "Authority Impersonation",
                description = "Caller claims to represent a cyber-crime or law-enforcement authority, but identity is not independently verified.",
                severity = DaRuleSeverity.HIGH,
                status = DaRuleStatus.DETECTED,
            )
        )
        add(
            DaRule(
                id = "RULE_02",
                title = "Digital Arrest Threat",
                description = "Caller uses arrest or detention threats to create urgency or intimidation.",
                severity = DaRuleSeverity.CRITICAL,
                status = DaRuleStatus.DETECTED,
            )
        )
        add(
            DaRule(
                id = "RULE_03",
                title = "Coercive Communication",
                description = "Communication attempts to force immediate compliance using fear, urgency, or intimidation.",
                severity = DaRuleSeverity.HIGH,
                status = DaRuleStatus.DETECTED,
            )
        )
        add(
            DaRule(
                id = "RULE_04",
                title = "Identity Verification Failure",
                description = "Claimed official identity cannot be verified through an independent trusted source.",
                severity = DaRuleSeverity.HIGH,
                status = DaRuleStatus.FAILED,
            )
        )
        if (financialPressureDetected) {
            add(
                DaRule(
                    id = "RULE_05",
                    title = "Financial / Information Pressure",
                    description = "Caller attempts to pressure the user into providing money, OTP, banking information, or other sensitive data.",
                    severity = DaRuleSeverity.CRITICAL,
                    status = DaRuleStatus.DETECTED,
                )
            )
        }
    }

    // ── Threat Assessment ─────────────────────────────────────────────────────

    fun buildThreatAssessment() = DaThreatAssessment(
        authorityImpersonation = 98,
        arrestThreat = 97,
        coercion = 94,
        identityVerification = 95,
        overallRisk = 96,
        severity = "CRITICAL",
    )

    // ── Timeline generation ───────────────────────────────────────────────────

    /**
     * Generates a realistic event timeline anchored to [triggerMs].
     *
     * Each event is placed slightly before or after the trigger,
     * with deterministic offsets so the timeline looks real.
     */
    fun buildTimeline(triggerMs: Long): List<DaTimeline> {
        val t = triggerMs
        return listOf(
            DaTimeline(t - 11_000, "WhatsApp video call initiated"),
            DaTimeline(t - 6_000, "Authority claim detected in communication"),
            DaTimeline(t - 4_000, "Arrest-related threat indicator detected"),
            DaTimeline(t, "Demo SMS trigger received — code 2000"),
            DaTimeline(t + 200, "Trigger condition matched (exact)"),
            DaTimeline(t + 800, "Evidence screenshot captured"),
            DaTimeline(t + 1_200, "Incident record created"),
            DaTimeline(t + 1_800, "Rule evaluation completed"),
            DaTimeline(t + 2_200, "Risk assessment completed (96 / 100 — CRITICAL)"),
            DaTimeline(t + 3_000, "Evidence bundle sealed"),
            DaTimeline(t + 3_500, "Incident report generated"),
            DaTimeline(t + 4_200, "Trusted contacts notified"),
        )
    }

    // ── Evidence builder ─────────────────────────────────────────────────────

    fun buildEvidence(
        screenshotPath: String?,
        screenshotBytes: ByteArray?,
        triggerMs: Long,
    ): List<DaEvidence> = buildList {

        val screenshotHash = screenshotBytes?.let { sha256Hex(it) }
            ?: "HASH_UNAVAILABLE_SCREEN_CAPTURE_RESTRICTED"

        add(
            DaEvidence(
                evidenceId = "EVD-001",
                type = "SCREENSHOT",
                filename = "incident-screen.png",
                capturedAt = triggerMs + 800,
                sha256 = screenshotHash,
                screenshotPath = screenshotPath,
            )
        )
        add(
            DaEvidence(
                evidenceId = "EVD-002",
                type = "COMMUNICATION_METADATA",
                filename = "communication-meta.json",
                capturedAt = triggerMs + 1_200,
                sha256 = sha256Hex("WhatsApp|VIDEO_CALL|${triggerMs}".toByteArray()),
            )
        )
        add(
            DaEvidence(
                evidenceId = "EVD-003",
                type = "THREAT_ANALYSIS",
                filename = "threat-analysis.json",
                capturedAt = triggerMs + 2_200,
                sha256 = sha256Hex("RISK:96|CRITICAL|${triggerMs}".toByteArray()),
            )
        )
        add(
            DaEvidence(
                evidenceId = "EVD-004",
                type = "INCIDENT_TIMELINE",
                filename = "incident-timeline.json",
                capturedAt = triggerMs + 3_000,
                sha256 = sha256Hex("TIMELINE|${triggerMs}".toByteArray()),
            )
        )
    }

    // ── Full incident builder ─────────────────────────────────────────────────

    fun buildIncident(
        screenshotPath: String?,
        screenshotBytes: ByteArray?,
    ): DigitalArrestIncident {
        val now = System.currentTimeMillis()
        val caseId = generateCaseId()
        val documentId = generateDocumentId()

        return DigitalArrestIncident(
            caseId = caseId,
            createdAt = now,
            triggerCode = "2000",
            triggerTimestamp = now,
            communicationPlatform = "WhatsApp",
            communicationType = "VIDEO_CALL",
            caller = DEMO_CALLER,
            threat = buildThreatAssessment(),
            rules = evaluateRules(financialPressureDetected = true),
            evidence = buildEvidence(screenshotPath, screenshotBytes, now),
            timeline = buildTimeline(now),
            documentId = documentId,
            notificationStatus = "PENDING",
            status = "ACTIVE",
        )
    }

    // ── Trusted-contact alert message ─────────────────────────────────────────

    fun buildTrustedContactMessage(caseId: String): String = """
TRINETRA SECURITY ALERT

A CRITICAL digital-arrest threat has been detected.

Case ID: $caseId
Threat Level: CRITICAL (96 / 100)

Trinetra has preserved the incident evidence and generated a security incident report.

Please contact the protected user immediately.

— Trinetra Digital Threat Response System
    """.trimIndent()

    // ── Integrity ─────────────────────────────────────────────────────────────

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
