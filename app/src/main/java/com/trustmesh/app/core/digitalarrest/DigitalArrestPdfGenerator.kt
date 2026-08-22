package com.trustmesh.app.core.digitalarrest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "DigitalArrestPdf"

/**
 * Generates a professional incident PDF using Android's native [android.graphics.pdf.PdfDocument].
 *
 * The document clearly labels itself as SYSTEM-GENERATED / SIMULATED and is
 * NOT represented as a government document, court order, or arrest warrant.
 */
object DigitalArrestPdfGenerator {

    private val dtFmt = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Page dimensions: A4 at 72 dpi
    private const val PAGE_W = 595
    private const val PAGE_H = 842

    // Brand colours (RGB)
    private val COL_NAVY = Color.rgb(17, 24, 45)        // #11182D primary dark
    private val COL_IVORY = Color.rgb(250, 250, 248)    // #FAFAF8 warm background
    private val COL_RED = Color.rgb(217, 77, 98)        // #D94D62 critical
    private val COL_GREEN = Color.rgb(58, 169, 104)     // #3AA968 safe
    private val COL_AMBER = Color.rgb(217, 154, 53)     // #D99A35 warn
    private val COL_BLUE = Color.rgb(102, 125, 255)     // #667DFF brand blue
    private val COL_DIVIDER = Color.rgb(228, 231, 236)  // #E4E7EC divider
    private val COL_SECONDARY = Color.rgb(98, 105, 120) // #626978 secondary text

    /**
     * Generates the PDF and saves it to [context.filesDir]/da_reports/<caseId>.pdf.
     *
     * @return The absolute path to the saved PDF, or null on failure.
     */
    fun generate(context: Context, incident: DigitalArrestIncident): String? {
        return try {
            val pdf = android.graphics.pdf.PdfDocument()

            // Page 1 — Incident Summary + Evidence
            val p1info = android.graphics.pdf.PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create()
            val page1 = pdf.startPage(p1info)
            drawPage1(page1.canvas, incident)
            pdf.finishPage(page1)

            // Page 2 — Rules + Risk + Timeline
            val p2info = android.graphics.pdf.PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 2).create()
            val page2 = pdf.startPage(p2info)
            drawPage2(page2.canvas, incident)
            pdf.finishPage(page2)

            // Page 3 — Formal Incident Notice
            val p3info = android.graphics.pdf.PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 3).create()
            val page3 = pdf.startPage(p3info)
            drawPage3(page3.canvas, incident)
            pdf.finishPage(page3)

            // Save
            val dir = File(context.filesDir, "da_reports").also { it.mkdirs() }
            val file = File(dir, "${incident.caseId}.pdf")
            FileOutputStream(file).use { pdf.writeTo(it) }
            pdf.close()

            Log.i(TAG, "PDF saved to ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "PDF generation failed", e)
            null
        }
    }

    // ── Page 1 ────────────────────────────────────────────────────────────────

    private fun drawPage1(canvas: Canvas, inc: DigitalArrestIncident) {
        val bg = Paint().apply { color = COL_IVORY; style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, PAGE_W.toFloat(), PAGE_H.toFloat(), bg)

        var y = 0f

        // ── Header strip ──────────────────────────────────────────────────────
        y = drawHeader(canvas, y, inc)

        y += 16f

        // ── Incident summary box ───────────────────────────────────────────────
        y = drawSectionTitle(canvas, y, "INCIDENT OVERVIEW")
        val fields = listOf(
            "Case ID" to inc.caseId,
            "Incident Type" to "Digital Arrest / Authority Impersonation",
            "Platform" to "${inc.communicationPlatform} ${inc.communicationType}",
            "Detection" to dtFmt.format(Date(inc.triggerTimestamp)),
            "Severity" to "CRITICAL",
            "Threat Score" to "${inc.threat.overallRisk} / 100",
            "Status" to "HIGH-RISK INCIDENT",
        )
        y = drawKeyValueTable(canvas, y, fields, critical = true)

        y += 12f

        // ── Executive summary ──────────────────────────────────────────────────
        y = drawSectionTitle(canvas, y, "EXECUTIVE SUMMARY")
        y = drawBodyText(
            canvas, y,
            "Trinetra detected a high-risk communication pattern involving an unverified " +
                "authority claim, arrest-related intimidation, and coercive communication. " +
                "The incident was automatically captured and preserved as a security evidence record. " +
                "All data below is SIMULATION / DEMO — not a real law-enforcement record.",
        )

        y += 12f

        // ── Caller section ────────────────────────────────────────────────────
        y = drawSectionTitle(canvas, y, "CALLER / SUSPECTED ACTOR")
        drawSimulatedBadge(canvas, y - 18f)
        val callerFields = listOf(
            "Display Name" to inc.caller.displayName,
            "Phone" to inc.caller.phoneNumber,
            "Claimed Organisation" to inc.caller.claimedAgency,
            "Claimed Role" to inc.caller.claimedRole,
            "Claimed Jurisdiction" to inc.caller.claimedJurisdiction,
            "Badge Number" to inc.caller.badgeNumber,
            "Identity Verification" to "UNVERIFIED ⚠",
        )
        y = drawKeyValueTable(canvas, y, callerFields, critical = false)

        y += 12f

        // ── Screenshot section ─────────────────────────────────────────────────
        y = drawSectionTitle(canvas, y, "COMMUNICATION EVIDENCE")
        y = drawScreenshotSection(canvas, y, inc)
    }

    // ── Page 2 ────────────────────────────────────────────────────────────────

    private fun drawPage2(canvas: Canvas, inc: DigitalArrestIncident) {
        val bg = Paint().apply { color = COL_IVORY; style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, PAGE_W.toFloat(), PAGE_H.toFloat(), bg)

        var y = 0f
        y = drawPageHeader(canvas, y, inc.caseId, "ANALYSIS & TIMELINE — PAGE 2")

        y += 8f

        // ── Rules table ───────────────────────────────────────────────────────
        y = drawSectionTitle(canvas, y, "DETECTED RULE VIOLATIONS")
        y = drawRulesTable(canvas, y, inc.rules)

        y += 12f

        // ── Risk scores ───────────────────────────────────────────────────────
        y = drawSectionTitle(canvas, y, "TRINETRA THREAT ANALYSIS")
        y = drawThreatBars(canvas, y, inc.threat)

        y += 12f

        // ── Response actions ──────────────────────────────────────────────────
        y = drawSectionTitle(canvas, y, "TRINETRA RESPONSE ACTIONS")
        y = drawResponseActions(canvas, y)

        y += 12f

        // ── Timeline ──────────────────────────────────────────────────────────
        y = drawSectionTitle(canvas, y, "INCIDENT TIMELINE")
        y = drawTimeline(canvas, y, inc.timeline)

        drawPageFooter(canvas, inc)
    }

    // ── Page 3 — Formal Incident Notice ─────────────────────────────────────

    private fun drawPage3(canvas: Canvas, inc: DigitalArrestIncident) {
        val bg = Paint().apply { color = COL_IVORY; style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, PAGE_W.toFloat(), PAGE_H.toFloat(), bg)

        var y = 0f

        // Top disclaimer bar
        val disclaimerBg = Paint().apply { color = Color.rgb(255, 243, 205); style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, PAGE_W.toFloat(), 44f, disclaimerBg)
        val disclaimerPaint = Paint().apply {
            color = Color.rgb(180, 100, 0); textSize = 11f; isFakeBoldText = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("⚠  SYSTEM-GENERATED SECURITY DOCUMENT — NOT A COURT-ISSUED WARRANT  ⚠", 36f, 27f, disclaimerPaint)
        y = 60f

        // Title
        val titlePaint = bigBoldPaint(COL_NAVY, 22f)
        canvas.drawText("DIGITAL ARREST INCIDENT NOTICE", PAGE_W / 2f, y, titlePaint.apply { textAlign = Paint.Align.CENTER })
        y += 30f

        val subPaint = bodyPaint(COL_SECONDARY, 11f)
        subPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Trinetra Digital Threat Response System — Simulated Security Document", PAGE_W / 2f, y, subPaint)
        y += 24f
        subPaint.textAlign = Paint.Align.LEFT

        drawDivider(canvas, y); y += 16f

        // Case number box
        y = drawKeyValueTable(canvas, y, listOf(
            "Case Number" to inc.caseId,
            "Subject" to "Suspected Digital Arrest / Authority Impersonation",
            "Generated" to dtFmt.format(Date(inc.createdAt)),
            "Document ID" to inc.documentId,
        ), critical = true)

        y += 16f

        // Basis for escalation
        y = drawSectionTitle(canvas, y, "BASIS FOR ESCALATION")
        y = drawBodyText(
            canvas, y,
            "High-confidence combination of authority impersonation, arrest-related " +
                "intimidation, coercive communication, and failed identity verification. " +
                "Trinetra risk score: 96 / 100 — CRITICAL."
        )

        y += 12f

        // Evidence references
        y = drawSectionTitle(canvas, y, "EVIDENCE REFERENCES")
        y = drawBodyText(canvas, y, "EVD-001 — Screenshot of active communication")
        y = drawBodyText(canvas, y, "EVD-002 — Communication metadata (platform, type, timestamp)")
        y = drawBodyText(canvas, y, "EVD-003 — Threat analysis record (risk scores, verdict)")
        y = drawBodyText(canvas, y, "EVD-004 — Incident timeline (all events with timestamps)")

        y += 12f

        // Recommended actions
        y = drawSectionTitle(canvas, y, "RECOMMENDED PROTECTIVE ACTION")
        val actions = listOf(
            "Terminate the suspicious communication immediately.",
            "Do not transfer money or disclose sensitive information.",
            "Preserve all communication evidence.",
            "Contact a trusted person (family/friend) right now.",
            "Seek appropriate human or legal assistance.",
            "File a complaint with the National Cyber Crime Helpline (1930).",
        )
        actions.forEach { action ->
            y = drawBodyText(canvas, y, "✓  $action")
        }

        y += 24f

        // Footer disclaimer box
        val footBg = Paint().apply { color = Color.rgb(240, 242, 255); style = Paint.Style.FILL }
        canvas.drawRoundRect(36f, y, PAGE_W - 36f, y + 120f, 8f, 8f, footBg)
        val footStroke = Paint().apply { color = COL_BLUE; style = Paint.Style.STROKE; strokeWidth = 1.5f }
        canvas.drawRoundRect(36f, y, PAGE_W - 36f, y + 120f, 8f, 8f, footStroke)

        y += 20f
        val footSmall = bodyPaint(COL_SECONDARY, 9f)
        val footBold = bodyPaint(COL_NAVY, 10f).apply { isFakeBoldText = true }
        canvas.drawText("Generated by: Trinetra Digital Threat Response System", 52f, y, footBold); y += 14f
        canvas.drawText("Document ID: ${inc.documentId}", 52f, y, footSmall); y += 12f
        canvas.drawText("Generated: ${dtFmt.format(Date(inc.createdAt))}", 52f, y, footSmall); y += 18f

        val warningPaint = bodyPaint(Color.rgb(180, 40, 40), 9f).apply { isFakeBoldText = true }
        canvas.drawText("NOT A GOVERNMENT DOCUMENT   ·   NOT A COURT ORDER   ·   NOT AN ARREST WARRANT", 52f, y, warningPaint)
    }

    // ── Drawing helpers ───────────────────────────────────────────────────────

    private fun drawHeader(canvas: Canvas, startY: Float, inc: DigitalArrestIncident): Float {
        // Navy header strip
        val headerBg = Paint().apply { color = COL_NAVY; style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, PAGE_W.toFloat(), 90f, headerBg)

        val appName = bigBoldPaint(Color.WHITE, 18f)
        appName.textAlign = Paint.Align.CENTER
        canvas.drawText("TRINETRA", PAGE_W / 2f, 30f, appName)

        val subtitle = bodyPaint(Color.rgb(180, 190, 220), 10f)
        subtitle.textAlign = Paint.Align.CENTER
        canvas.drawText("DIGITAL THREAT RESPONSE SYSTEM", PAGE_W / 2f, 48f, subtitle)

        val docTitle = bigBoldPaint(Color.rgb(217, 77, 98), 13f)
        docTitle.textAlign = Paint.Align.CENTER
        canvas.drawText("DIGITAL ARREST INCIDENT & EVIDENCE REPORT", PAGE_W / 2f, 68f, docTitle)

        val caseLabel = bodyPaint(Color.rgb(140, 150, 180), 9f)
        caseLabel.textAlign = Paint.Align.CENTER
        canvas.drawText("${inc.caseId}   ·   SIMULATED DEMO DOCUMENT", PAGE_W / 2f, 84f, caseLabel)

        return 98f
    }

    private fun drawPageHeader(canvas: Canvas, startY: Float, caseId: String, label: String): Float {
        val stripBg = Paint().apply { color = COL_NAVY; style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, PAGE_W.toFloat(), 44f, stripBg)

        val p = bodyPaint(Color.WHITE, 10f)
        p.isFakeBoldText = true
        canvas.drawText("TRINETRA   ·   $caseId", 20f, 20f, p)

        val p2 = bodyPaint(Color.rgb(160, 170, 200), 9f)
        canvas.drawText(label, 20f, 36f, p2)

        return 56f
    }

    private fun drawSectionTitle(canvas: Canvas, y: Float, title: String): Float {
        drawDivider(canvas, y)
        val p = bodyPaint(COL_BLUE, 9f).apply { isFakeBoldText = true }
        canvas.drawText(title, 36f, y + 14f, p)
        return y + 22f
    }

    private fun drawKeyValueTable(canvas: Canvas, startY: Float, fields: List<Pair<String, String>>, critical: Boolean): Float {
        var y = startY + 4f
        val rowH = 18f
        val keyPaint = bodyPaint(COL_SECONDARY, 9f)
        val valPaint = bodyPaint(COL_NAVY, 10f).apply { isFakeBoldText = true }
        val critPaint = bodyPaint(COL_RED, 10f).apply { isFakeBoldText = true }

        fields.forEach { (key, value) ->
            val isRedValue = critical && (value.contains("CRITICAL") || value.contains("HIGH-RISK") || value.contains("UNVERIFIED"))
            val bgPaint = Paint().apply {
                color = if (isRedValue) Color.argb(18, 217, 77, 98) else Color.TRANSPARENT
                style = Paint.Style.FILL
            }
            canvas.drawRect(36f, y - 12f, PAGE_W - 36f, y + 6f, bgPaint)

            canvas.drawText(key.uppercase(), 40f, y, keyPaint)
            val vp = if (isRedValue) critPaint else valPaint
            canvas.drawText(value, 200f, y, vp)
            y += rowH
        }
        return y + 4f
    }

    private fun drawBodyText(canvas: Canvas, y: Float, text: String, maxWidth: Int = PAGE_W - 72): Float {
        val p = bodyPaint(COL_SECONDARY, 9f)
        val lineHeight = 13f
        // Simple word-wrap
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = ""
        words.forEach { word ->
            val test = if (current.isEmpty()) word else "$current $word"
            if (p.measureText(test) < maxWidth) {
                current = test
            } else {
                if (current.isNotEmpty()) lines.add(current)
                current = word
            }
        }
        if (current.isNotEmpty()) lines.add(current)

        var cy = y
        lines.forEach { line ->
            canvas.drawText(line, 40f, cy, p)
            cy += lineHeight
        }
        return cy + 4f
    }

    private fun drawSimulatedBadge(canvas: Canvas, y: Float) {
        val p = bodyPaint(COL_AMBER, 8f).apply { isFakeBoldText = true }
        p.textAlign = Paint.Align.RIGHT
        canvas.drawText("⚠ SIMULATED / USER-PROVIDED DEMONSTRATION DATA", PAGE_W - 36f, y, p)
        p.textAlign = Paint.Align.LEFT
    }

    private fun drawScreenshotSection(canvas: Canvas, startY: Float, inc: DigitalArrestIncident): Float {
        var y = startY + 4f
        val evidence = inc.evidence.firstOrNull { it.type == "SCREENSHOT" }

        // Screenshot box
        val boxTop = y
        val boxH = 110f
        val boxBg = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        val boxStroke = Paint().apply { color = COL_DIVIDER; style = Paint.Style.STROKE; strokeWidth = 1f }
        canvas.drawRoundRect(40f, boxTop, PAGE_W - 40f, boxTop + boxH, 6f, 6f, boxBg)
        canvas.drawRoundRect(40f, boxTop, PAGE_W - 40f, boxTop + boxH, 6f, 6f, boxStroke)

        // Try to load the actual screenshot
        val bitmapLoaded = evidence?.screenshotPath?.let { path ->
            try {
                val bmp = BitmapFactory.decodeFile(path)
                if (bmp != null) {
                    val scale = minOf(
                        (PAGE_W - 100f) / bmp.width,
                        (boxH - 16f) / bmp.height,
                    )
                    val dstW = (bmp.width * scale).toInt()
                    val dstH = (bmp.height * scale).toInt()
                    val left = ((PAGE_W - dstW) / 2f)
                    val top = boxTop + (boxH - dstH) / 2f
                    val dst = Rect(left.toInt(), top.toInt(), (left + dstW).toInt(), (top + dstH).toInt())
                    canvas.drawBitmap(bmp, null, dst, null)
                    true
                } else false
            } catch (e: Exception) {
                false
            }
        } ?: false

        if (!bitmapLoaded) {
            val placeholder = bodyPaint(COL_SECONDARY, 9f)
            placeholder.textAlign = Paint.Align.CENTER
            canvas.drawText("[SCREEN CAPTURE — EVIDENCE SCREENSHOT]", PAGE_W / 2f, boxTop + boxH / 2f - 8f, placeholder)
            canvas.drawText("WhatsApp video call active at time of trigger", PAGE_W / 2f, boxTop + boxH / 2f + 8f, placeholder)
            placeholder.textAlign = Paint.Align.LEFT
        }

        y = boxTop + boxH + 8f

        // Evidence metadata
        if (evidence != null) {
            val meta = bodyPaint(COL_SECONDARY, 8f)
            canvas.drawText("Evidence ID: ${evidence.evidenceId}   ·   Captured: ${dtFmt.format(Date(evidence.capturedAt))}", 40f, y, meta)
            y += 12f
            canvas.drawText("SHA-256: ${evidence.sha256.take(48)}...", 40f, y, meta)
            y += 10f
        }

        return y
    }

    private fun drawRulesTable(canvas: Canvas, startY: Float, rules: List<DaRule>): Float {
        var y = startY + 4f
        val rowH = 22f

        // Header
        val headerBg = Paint().apply { color = Color.rgb(240, 242, 255); style = Paint.Style.FILL }
        canvas.drawRect(36f, y - 12f, PAGE_W - 36f, y + 8f, headerBg)
        val headerP = bodyPaint(COL_NAVY, 9f).apply { isFakeBoldText = true }
        canvas.drawText("RULE", 44f, y, headerP)
        canvas.drawText("SEVERITY", 280f, y, headerP)
        canvas.drawText("STATUS", 400f, y, headerP)
        y += rowH

        rules.forEach { rule ->
            val rowBg = Paint().apply {
                color = if (rule.severity == DaRuleSeverity.CRITICAL) Color.argb(12, 217, 77, 98) else Color.TRANSPARENT
                style = Paint.Style.FILL
            }
            canvas.drawRect(36f, y - 14f, PAGE_W - 36f, y + 6f, rowBg)

            val ruleP = bodyPaint(COL_NAVY, 8f)
            canvas.drawText(rule.title, 44f, y, ruleP)

            val sevP = bodyPaint(
                if (rule.severity == DaRuleSeverity.CRITICAL) COL_RED else COL_AMBER, 8f
            ).apply { isFakeBoldText = true }
            canvas.drawText(rule.severity.name, 280f, y, sevP)

            val statusCol = if (rule.status == DaRuleStatus.DETECTED) COL_RED
            else if (rule.status == DaRuleStatus.FAILED) COL_AMBER else COL_SECONDARY
            val statusP = bodyPaint(statusCol, 8f).apply { isFakeBoldText = true }
            canvas.drawText(rule.status.name, 400f, y, statusP)

            y += rowH
        }
        return y + 4f
    }

    private fun drawThreatBars(canvas: Canvas, startY: Float, threat: DaThreatAssessment): Float {
        var y = startY + 4f
        val barW = 200f
        val barH = 10f
        val rowH = 22f

        val items = listOf(
            "Authority Impersonation" to threat.authorityImpersonation,
            "Arrest Threat" to threat.arrestThreat,
            "Coercion" to threat.coercion,
            "Identity Verification" to threat.identityVerification,
        )

        items.forEach { (label, score) ->
            val labelP = bodyPaint(COL_SECONDARY, 9f)
            canvas.drawText(label, 40f, y, labelP)

            // Background bar
            val bgBar = Paint().apply { color = COL_DIVIDER; style = Paint.Style.FILL }
            canvas.drawRoundRect(230f, y - 8f, 230f + barW, y - 8f + barH, 5f, 5f, bgBar)

            // Filled bar
            val fill = Paint().apply { color = COL_RED; style = Paint.Style.FILL }
            canvas.drawRoundRect(230f, y - 8f, 230f + barW * score / 100f, y - 8f + barH, 5f, 5f, fill)

            val scoreP = bodyPaint(COL_NAVY, 9f).apply { isFakeBoldText = true }
            canvas.drawText("$score%", 445f, y, scoreP)

            y += rowH
        }

        // Overall
        y += 4f
        drawDivider(canvas, y); y += 12f
        val overallP = bigBoldPaint(COL_RED, 14f)
        canvas.drawText("OVERALL RISK:  ${threat.overallRisk} / 100   ·   ${threat.severity}", 40f, y, overallP)
        y += 20f
        val noteP = bodyPaint(COL_SECONDARY, 8f)
        canvas.drawText("TRINETRA RISK ASSESSMENT — Not a legal determination", 40f, y, noteP)
        y += 14f

        return y
    }

    private fun drawResponseActions(canvas: Canvas, startY: Float): Float {
        var y = startY + 4f
        val actions = listOf(
            "Incident created and logged",
            "Communication evidence preserved",
            "Evidence screenshot captured",
            "Threat assessment completed (96 / 100 — CRITICAL)",
            "Evidence integrity hash calculated (SHA-256)",
            "Trusted contacts notified",
            "Incident escalated for human review",
        )
        actions.forEach { action ->
            val p = bodyPaint(COL_GREEN, 9f)
            canvas.drawText("✓  $action", 44f, y, p)
            y += 16f
        }
        return y + 4f
    }

    private fun drawTimeline(canvas: Canvas, startY: Float, timeline: List<DaTimeline>): Float {
        var y = startY + 8f
        val dotPaint = Paint().apply { color = COL_BLUE; style = Paint.Style.FILL }
        val linePaint = Paint().apply { color = COL_DIVIDER; style = Paint.Style.STROKE; strokeWidth = 1.5f }

        timeline.forEachIndexed { idx, entry ->
            // Connector line
            if (idx > 0) {
                canvas.drawLine(50f, y - 14f, 50f, y - 4f, linePaint)
            }
            // Dot
            canvas.drawCircle(50f, y, 4f, dotPaint)

            // Time
            val timePaint = bodyPaint(COL_SECONDARY, 8f)
            canvas.drawText(timeFmt.format(Date(entry.timestampMs)), 64f, y + 4f, timePaint)

            // Label
            val labelPaint = bodyPaint(COL_NAVY, 9f)
            canvas.drawText(entry.label, 140f, y + 4f, labelPaint)

            y += 20f
        }
        return y + 8f
    }

    private fun drawDivider(canvas: Canvas, y: Float) {
        val p = Paint().apply { color = COL_DIVIDER; style = Paint.Style.STROKE; strokeWidth = 0.7f }
        canvas.drawLine(36f, y, PAGE_W - 36f, y, p)
    }

    private fun drawPageFooter(canvas: Canvas, inc: DigitalArrestIncident) {
        drawDivider(canvas, PAGE_H - 28f)
        val p = bodyPaint(COL_SECONDARY, 8f)
        canvas.drawText("Trinetra Digital Threat Response System   ·   ${inc.caseId}   ·   SIMULATION / NOT A LEGAL DOCUMENT", 36f, PAGE_H - 14f, p)
    }

    // ── Paint factories ───────────────────────────────────────────────────────

    private fun bodyPaint(color: Int, textSizeSp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.textSize = textSizeSp * 1.33f // approx sp → px at 96dpi
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private fun bigBoldPaint(color: Int, textSizeSp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.textSize = textSizeSp * 1.33f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        isFakeBoldText = true
    }
}
