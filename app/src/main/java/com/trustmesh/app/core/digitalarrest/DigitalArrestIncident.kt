package com.trustmesh.app.core.digitalarrest

/**
 * Complete data model for a Digital Arrest (DA) incident.
 * This is DEMO / SIMULATION data — not a real law-enforcement record.
 */

enum class DaRuleSeverity { HIGH, CRITICAL }

enum class DaRuleStatus { DETECTED, FAILED, NOT_TRIGGERED }

data class DaRule(
    val id: String,
    val title: String,
    val description: String,
    val severity: DaRuleSeverity,
    val status: DaRuleStatus,
)

data class DaEvidence(
    val evidenceId: String,             // e.g. EVD-001
    val type: String,                   // SCREENSHOT | COMMUNICATION_METADATA | THREAT_ANALYSIS | TIMELINE
    val filename: String,
    val capturedAt: Long,               // epoch-ms
    val sha256: String,                 // hex digest of the image bytes (or placeholder)
    val screenshotPath: String? = null, // absolute path to the saved PNG, null if unavailable
)

data class DaTimeline(
    val timestampMs: Long,
    val label: String,
)

data class DaCaller(
    val displayName: String,
    val phoneNumber: String,
    val claimedAgency: String,
    val claimedRole: String,
    val claimedJurisdiction: String,
    val badgeNumber: String,
    val identityVerification: String = "UNVERIFIED",
)

data class DaThreatAssessment(
    val authorityImpersonation: Int = 98,
    val arrestThreat: Int = 97,
    val coercion: Int = 94,
    val identityVerification: Int = 95,
    val overallRisk: Int = 96,
    val severity: String = "CRITICAL",
)

/**
 * Top-level incident record.
 *
 * [caseId] format: TRN-DA-YYYYMMDD-XXXXXX
 * [reportPath] is the absolute path to the generated PDF, null until generation completes.
 * [notificationStatus] is SENT | PENDING | FAILED.
 */
data class DigitalArrestIncident(
    val caseId: String,
    val createdAt: Long,

    // Trigger metadata
    val triggerCode: String = "2000",
    val triggerTimestamp: Long = System.currentTimeMillis(),

    // Communication channel
    val communicationPlatform: String = "WhatsApp",
    val communicationType: String = "VIDEO_CALL",

    // Demo caller (SIMULATED / USER-PROVIDED)
    val caller: DaCaller,

    // Threat assessment
    val threat: DaThreatAssessment = DaThreatAssessment(),

    // Rules fired
    val rules: List<DaRule>,

    // Evidence artefacts
    val evidence: List<DaEvidence> = emptyList(),

    // Auto-generated event timeline
    val timeline: List<DaTimeline> = emptyList(),

    // Report generation
    val reportPath: String? = null,
    val documentId: String,

    // Notification
    val notificationStatus: String = "PENDING",

    val status: String = "ACTIVE",
)
