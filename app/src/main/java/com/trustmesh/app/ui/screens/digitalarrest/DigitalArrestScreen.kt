package com.trustmesh.app.ui.screens.digitalarrest

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trustmesh.app.core.digitalarrest.*
import com.trustmesh.app.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private val CriticalRed = Color(0xFFD94D62)
private val SafeGreen    = Color(0xFF3AA968)
private val NavyDark     = Color(0xFF11182D)
private val IvoryBg      = Color(0xFFFAFAF8)
private val CardWhite    = Color.White
private val TextGray     = Color(0xFF626978)

@Composable
fun DigitalArrestScreen(onBack: () -> Unit) {
    val state by DigitalArrestController.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    when (state.phase) {
        DaWorkflowPhase.IDLE    -> DaIdleContent(context, onBack)
        DaWorkflowPhase.COMPLETE -> DaCriticalDashboard(state, context, onBack)
        DaWorkflowPhase.ERROR   -> DaErrorContent(state.errorMessage, context, onBack)
        else                    -> DaProgressContent(state.phase)
    }
}

// ── Idle state ────────────────────────────────────────────────────────────────

@Composable
private fun DaIdleContent(context: Context, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(IvoryBg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Shield, null, Modifier.size(64.dp), tint = Color(0xFF667DFF))
        Spacer(Modifier.height(16.dp))
        Text("DIGITAL ARREST PROTECTION", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = NavyDark, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Monitoring for trigger SMS \"2000\".\nSend that SMS to activate the full demo workflow.", fontSize = 13.sp, color = TextGray, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { DigitalArrestController.simulateTrigger(context) },
            colors = ButtonDefaults.buttonColors(containerColor = CriticalRed),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Icon(Icons.Default.PlayArrow, null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("SIMULATE TRIGGER", fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack) { Text("← Back", color = TextGray) }
    }
}

// ── Progress animation ────────────────────────────────────────────────────────

private val PHASE_LABELS = mapOf(
    DaWorkflowPhase.SMS_DETECTED        to "SMS RECEIVED",
    DaWorkflowPhase.TRIGGER_VERIFIED    to "TRIGGER VERIFIED",
    DaWorkflowPhase.CAPTURING_EVIDENCE  to "CAPTURING EVIDENCE",
    DaWorkflowPhase.COLLECTING_METADATA to "COLLECTING METADATA",
    DaWorkflowPhase.EVALUATING_RULES    to "EVALUATING RULES",
    DaWorkflowPhase.SCORING_RISK        to "SCORING RISK",
    DaWorkflowPhase.SEALING_EVIDENCE    to "SEALING EVIDENCE",
    DaWorkflowPhase.GENERATING_REPORT   to "GENERATING CASE REPORT",
    DaWorkflowPhase.NOTIFYING_CONTACTS  to "NOTIFYING TRUSTED CONTACTS",
)

private val ALL_PHASES = listOf(
    DaWorkflowPhase.SMS_DETECTED,
    DaWorkflowPhase.TRIGGER_VERIFIED,
    DaWorkflowPhase.CAPTURING_EVIDENCE,
    DaWorkflowPhase.COLLECTING_METADATA,
    DaWorkflowPhase.EVALUATING_RULES,
    DaWorkflowPhase.SCORING_RISK,
    DaWorkflowPhase.SEALING_EVIDENCE,
    DaWorkflowPhase.GENERATING_REPORT,
    DaWorkflowPhase.NOTIFYING_CONTACTS,
)

@Composable
private fun DaProgressContent(current: DaWorkflowPhase) {
    val infiniteT = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteT.animateFloat(0.6f, 1f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0B0D10), Color(0xFF1A0709), Color(0xFF0B0D10)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(80.dp).scale(pulse).clip(CircleShape)
                    .background(CriticalRed.copy(alpha = 0.2f))
                    .border(2.dp, CriticalRed.copy(alpha = pulse), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Security, null, Modifier.size(40.dp), tint = CriticalRed)
            }
            Spacer(Modifier.height(24.dp))
            Text("DIGITAL ARREST PROTECTION", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                color = Color.White, letterSpacing = 1.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text("WORKFLOW ACTIVE", fontSize = 11.sp, color = CriticalRed, fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp)
            Spacer(Modifier.height(32.dp))

            ALL_PHASES.forEachIndexed { idx, phase ->
                val done    = ALL_PHASES.indexOf(current) > idx
                val active  = phase == current
                val pending = !done && !active
                DaPhaseRow(label = PHASE_LABELS[phase] ?: phase.name, done = done, active = active, pulse = pulse)
                if (idx < ALL_PHASES.lastIndex) Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DaPhaseRow(label: String, done: Boolean, active: Boolean, pulse: Float) {
    val color = when {
        done   -> SafeGreen
        active -> CriticalRed.copy(alpha = pulse)
        else   -> Color(0xFF3A3F4B)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (done) Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint = SafeGreen)
            else if (active) CircularProgressIndicator(Modifier.size(16.dp), color = CriticalRed, strokeWidth = 2.dp)
            else Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF3A3F4B)))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 12.sp, color = color, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
    }
}

// ── CRITICAL Dashboard ────────────────────────────────────────────────────────

@Composable
private fun DaCriticalDashboard(state: DigitalArrestState, context: Context, onBack: () -> Unit) {
    val inc = state.incident ?: return
    val scroll = rememberScrollState()
    val infiniteT = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteT.animateFloat(0.5f, 1f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")

    Column(
        Modifier.fillMaxSize().background(IvoryBg).verticalScroll(scroll)
    ) {
        // Critical header
        Box(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xFF1A0709), Color(0xFF2A0E14))))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(72.dp).scale(0.9f + 0.1f * pulse).clip(CircleShape)
                        .background(CriticalRed.copy(0.15f)).border(2.dp, CriticalRed.copy(pulse), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.GppBad, null, Modifier.size(36.dp), tint = CriticalRed)
                }
                Spacer(Modifier.height(12.dp))
                Text("CRITICAL THREAT DETECTED", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    color = CriticalRed, letterSpacing = 1.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text("Digital Arrest / Authority Impersonation", fontSize = 13.sp, color = Color(0xFFB0B8C4), textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                // Big score
                Text("${inc.threat.overallRisk}", fontSize = 64.sp, fontWeight = FontWeight.Bold,
                    color = CriticalRed, textAlign = TextAlign.Center)
                Text("/ 100   CRITICAL", fontSize = 14.sp, color = Color(0xFFB0B8C4), textAlign = TextAlign.Center)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Status pills
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("Evidence", "SECURED", SafeGreen, Modifier.weight(1f))
            StatusPill("Report",   "GENERATED", Color(0xFF667DFF), Modifier.weight(1f))
            StatusPill("Contacts", if (inc.notificationStatus == "SENT") "NOTIFIED" else "PENDING",
                if (inc.notificationStatus == "SENT") SafeGreen else Color(0xFFD99A35), Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))

        // Incident info card
        DaCard(Modifier.padding(horizontal = 16.dp)) {
            DaFieldRow("Case ID", inc.caseId)
            DaFieldRow("Caller", inc.caller.displayName)
            DaFieldRow("Agency", inc.caller.claimedAgency)
            DaFieldRow("Identity", "UNVERIFIED", CriticalRed)
            DaFieldRow("Platform", "${inc.communicationPlatform} ${inc.communicationType}")
        }

        Spacer(Modifier.height(12.dp))

        // Threat bars
        DaCard(Modifier.padding(horizontal = 16.dp)) {
            Text("TRINETRA THREAT ANALYSIS", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFF667DFF), letterSpacing = 1.sp)
            Spacer(Modifier.height(12.dp))
            DaThreatBar("Authority Impersonation", inc.threat.authorityImpersonation)
            DaThreatBar("Arrest Threat", inc.threat.arrestThreat)
            DaThreatBar("Coercion", inc.threat.coercion)
            DaThreatBar("Identity Verification", inc.threat.identityVerification)
            Spacer(Modifier.height(8.dp))
            Divider(color = Color(0xFFE4E7EC))
            Spacer(Modifier.height(8.dp))
            Text("OVERALL RISK: ${inc.threat.overallRisk} / 100 — ${inc.threat.severity}",
                fontWeight = FontWeight.Bold, fontSize = 14.sp, color = CriticalRed)
            Text("Trinetra Risk Assessment — Not a legal determination", fontSize = 9.sp, color = TextGray)
        }

        Spacer(Modifier.height(12.dp))

        // Rules
        DaCard(Modifier.padding(horizontal = 16.dp)) {
            Text("DETECTED VIOLATIONS", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFF667DFF), letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            inc.rules.forEach { rule ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(rule.title, fontSize = 11.sp, color = NavyDark, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    val sevColor = if (rule.severity == DaRuleSeverity.CRITICAL) CriticalRed else Color(0xFFD99A35)
                    Text(rule.severity.name, fontSize = 10.sp, color = sevColor, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(rule.status.name, fontSize = 10.sp, color = CriticalRed, fontWeight = FontWeight.Bold)
                }
                Divider(color = Color(0xFFE4E7EC), thickness = 0.5.dp)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Evidence hash
        inc.evidence.firstOrNull()?.let { ev ->
            DaCard(Modifier.padding(horizontal = 16.dp)) {
                Text("EVIDENCE INTEGRITY", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFF667DFF), letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                Text("SHA-256", fontSize = 10.sp, color = TextGray)
                Text(ev.sha256.take(48) + "...", fontSize = 9.sp, color = NavyDark, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Action buttons
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.reportPath?.let { path ->
                Button(
                    onClick = { openPdf(context, path) },
                    colors = ButtonDefaults.buttonColors(containerColor = NavyDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Default.PictureAsPdf, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("VIEW INCIDENT REPORT", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            OutlinedButton(
                onClick = { DigitalArrestController.reset() },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                border = BorderStroke(1.dp, Color(0xFFE4E7EC))
            ) {
                Text("RESET DEMO", color = TextGray)
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("← Back to Home", color = TextGray)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DaCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun DaFieldRow(key: String, value: String, valueColor: Color = NavyDark) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, fontSize = 11.sp, color = TextGray)
        Text(value, fontSize = 11.sp, color = valueColor, fontWeight = FontWeight.Medium, textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 8.dp))
    }
}

@Composable
private fun DaThreatBar(label: String, score: Int) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 11.sp, color = NavyDark)
            Text("$score%", fontSize = 11.sp, color = CriticalRed, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(3.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFE4E7EC))) {
            Box(Modifier.fillMaxWidth(score / 100f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(CriticalRed))
        }
    }
}

@Composable
private fun StatusPill(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 9.sp, color = TextGray, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(value, fontSize = 10.sp, color = color, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DaErrorContent(msg: String?, context: Context, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(IvoryBg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Error, null, Modifier.size(48.dp), tint = CriticalRed)
        Spacer(Modifier.height(12.dp))
        Text("Workflow Error", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = NavyDark)
        Spacer(Modifier.height(8.dp))
        Text(msg ?: "Unknown error", fontSize = 12.sp, color = TextGray, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { DigitalArrestController.simulateTrigger(context) },
            colors = ButtonDefaults.buttonColors(containerColor = CriticalRed),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("RETRY", fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack) { Text("← Back", color = TextGray) }
    }
}

private fun openPdf(context: Context, path: String) {
    try {
        val file = File(path)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.provider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        android.util.Log.e("DaScreen", "Cannot open PDF", e)
    }
}
