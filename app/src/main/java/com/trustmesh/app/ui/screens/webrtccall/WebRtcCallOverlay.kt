package com.trustmesh.app.ui.screens.webrtccall

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trustmesh.app.callaudio.webrtc.AlertLevel
import com.trustmesh.app.callaudio.webrtc.WebRtcIntelligenceState
import com.trustmesh.app.callaudio.webrtc.toHumanLabel

// ── Design tokens ─────────────────────────────────────────────────────────────
private val Ivory        = Color(0xFFFAF8F5)
private val Ink          = Color(0xFF0F172A)
private val Slate        = Color(0xFF64748B)
private val Cyan         = Color(0xFF028090)
private val DangerRed    = Color(0xFFDC2626)
private val WarnAmber    = Color(0xFFD97706)
private val SafeGreen    = Color(0xFF1B8A5A)
private val CardBg       = Color(0xFFF1EDE4)

private val AlertLevel.themeColor: Color
    get() = when (this) {
        AlertLevel.CRITICAL   -> DangerRed
        AlertLevel.ELEVATED   -> WarnAmber
        AlertLevel.MONITORING -> Cyan
        AlertLevel.CLEAR      -> SafeGreen
    }

// ── Compact pill ─────────────────────────────────────────────────────────────

/**
 * The compact pill shown at the top of the screen during a call.
 * Tapping it expands to the full overlay.
 * Automatically expands when alertLevel ≥ ELEVATED.
 */
@Composable
fun WebRtcCallPill(
    state: WebRtcIntelligenceState,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pulse = rememberInfiniteTransition(label = "pill_pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "dot_alpha"
    )
    val color = state.alertLevel.themeColor
    val score = state.fusedRiskScore

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ivory)
            .border(BorderStroke(1.5.dp, color), RoundedCornerShape(12.dp))
            .clickable { onExpand() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Live dot
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = alpha))
        )
        // Name + clone
        Column(Modifier.weight(1f)) {
            Text(
                text = state.remoteName.ifBlank { "WebRTC Call" },
                color = Ink,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1
            )
            val cloneText = state.cloneScore?.let {
                "Clone: ${"%.0f".format(it * 100)}%"
            } ?: "Checking voice…"
            val sttText = state.latestTranscriptChunk.take(40).ifBlank { null }
            Text(
                text = if (sttText != null) "\"$sttText…\"" else cloneText,
                color = Slate,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Risk badge
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "$score%",
                color = color,
                fontWeight = FontWeight.Black,
                fontSize = 16.sp
            )
            Text(
                text = state.alertLevel.displayLabel.uppercase(),
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp
            )
        }
    }
}

// ── Full expanded overlay ─────────────────────────────────────────────────────

/**
 * The full real-time intelligence overlay, shown when risk is ELEVATED or CRITICAL,
 * or when the user taps the compact pill.
 *
 * Shows:
 *  - VCD clone score + verdict
 *  - Live transcript (Hindi + English)
 *  - Groq L3 intent / L4 tactics / L5 context
 *  - Oscilloscope-style risk bar
 *  - Safety advice when risk is high
 */
@Composable
fun WebRtcCallOverlay(
    state: WebRtcIntelligenceState,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val color = state.alertLevel.themeColor
    val score = state.fusedRiskScore

    // Vibrate on ELEVATED / CRITICAL transitions
    LaunchedEffect(state.alertLevel) {
        if (state.alertLevel.ordinal >= AlertLevel.ELEVATED.ordinal) {
            runCatching {
                val v = context.getSystemService(android.content.Context.VIBRATOR_SERVICE)
                        as? android.os.Vibrator
                if (v?.hasVibrator() == true) {
                    val timings = longArrayOf(0, 400, 150, 400)
                    val amplitudes = intArrayOf(0, 255, 0, 255)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        v.vibrate(android.os.VibrationEffect.createWaveform(timings, amplitudes, -1))
                    } else {
                        @Suppress("DEPRECATION")
                        v.vibrate(timings, -1)
                    }
                }
            }
        }
    }

    val animScore by animateFloatAsState(
        targetValue = score.toFloat(),
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "risk_anim"
    )

    Column(
        // No verticalScroll here: this overlay is rendered inside ActiveCallScreen's outer
        // Column(Modifier.verticalScroll(...)). Nesting a second vertical scroll hands this one an
        // infinite max-height constraint, which Compose rejects with IllegalStateException — the
        // crash seen when expanding the intelligence panel mid-call. The outer scroll covers it.
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Ivory)
            .border(BorderStroke(2.dp, color), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("TRINETRA", color = Ink, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp)
                Text("● WEBRTC LIVE INTELLIGENCE", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 8.sp)
            }
            TextButton(onClick = onCollapse) {
                Text("Collapse", color = Slate, fontSize = 11.sp)
            }
        }

        // ── Caller + risk badge ───────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .background(CardBg, RoundedCornerShape(12.dp))
                .border(BorderStroke(1.dp, Color(0xFFE2E8F0)), RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(Ink, RoundedCornerShape(19.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    state.remoteName.take(1).uppercase().ifBlank { "?" },
                    color = Ivory,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(state.remoteName.ifBlank { "Unknown" }, color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(state.vcdVerdict, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${animScore.toInt()}%", color = color, fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text(state.alertLevel.displayLabel, color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ── VCD clone score bar ───────────────────────────────────────────
        IntelligenceSection(
            emoji = "🎙",
            title = "VOICE CLONE DETECTION",
            color = color
        ) {
            val clone = state.cloneScore
            if (clone == null) {
                Text("Listening… needs ~12 s of audio", color = Slate, fontSize = 11.sp)
            } else {
                val pct = (clone * 100).toInt()
                val verdict = when {
                    pct >= 70 -> "⚠ HIGH CLONE PROBABILITY"
                    pct >= 40 -> "Possibly synthetic"
                    else      -> "✓ Appears genuine"
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(verdict, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("$pct%", color = color, fontWeight = FontWeight.Black, fontSize = 14.sp)
                }
                Spacer(Modifier.height(4.dp))
                RiskBar(score = pct, color = color)
            }
            val matched = state.identityMatch
            if (matched != null) {
                Text(
                    if (matched) "✓ Identity matches enrolled voiceprint"
                    else "✗ Does not match enrolled voiceprint",
                    color = if (matched) SafeGreen else DangerRed,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // ── Live transcript ───────────────────────────────────────────────
        IntelligenceSection(
            emoji = "🗣",
            title = "LIVE TRANSCRIPT (Hindi + English)",
            color = Cyan
        ) {
            if (state.latestTranscriptChunk.isBlank() && state.fullTranscript.isBlank()) {
                Text("Waiting for speech…", color = Slate, fontSize = 11.sp)
            } else {
                val langLabel = when (state.transcriptLanguage) {
                    "hi" -> " [Hindi]"
                    "en" -> " [English]"
                    else -> ""
                }
                Text(
                    text = "\"${state.fullTranscript.takeLast(200)}\"$langLabel",
                    color = Ink,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                if (state.latestTranscriptChunk.isNotBlank()) {
                    Text(
                        text = "→ ${state.latestTranscriptChunk}",
                        color = Cyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // ── Groq L3/L4/L5 analysis ────────────────────────────────────────
        val groq = state.groqResult
        if (groq != null || state.wordsHeard.isNotEmpty()) {
            IntelligenceSection(
                emoji = "🧠",
                title = "AI THREAT ANALYSIS (L3/L4/L5)",
                color = WarnAmber
            ) {
                // L3 — Intent
                LabelRow("L3 INTENT", state.inferredIntent.ifBlank { "Analyzing…" }, WarnAmber)
                // L4 — Tactics
                val tactics = state.tacticsDetected.joinToString(" · ").ifBlank { "None detected" }
                LabelRow("L4 TACTICS", tactics, DangerRed)
                // L5 — Context (from attack context)
                val context5 = groq?.groq?.scamCategory?.toHumanLabel() ?: "—"
                LabelRow("L5 CONTEXT", context5, color)

                // Key words caught
                if (state.wordsHeard.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("⚡ WORDS CAUGHT:", color = Slate, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(
                        state.wordsHeard.joinToString(" · "),
                        color = DangerRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                groq?.groq?.summaryReasoning?.let { summary ->
                    Spacer(Modifier.height(4.dp))
                    Text(summary, color = Ink, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }

        // ── Overall risk bar ──────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .background(CardBg, RoundedCornerShape(12.dp))
                .border(BorderStroke(1.dp, Color(0xFFE2E8F0)), RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("THREAT SCORE", color = Ink, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Text("${animScore.toInt()}%", color = color, fontWeight = FontWeight.Black, fontSize = 14.sp)
            }
            Spacer(Modifier.height(6.dp))
            RiskBar(score = animScore.toInt(), color = color)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("VCD Clone", "STT", "L3 Intent", "L4 Tactics", "L5 Ctx").forEach { label ->
                    Text(label, color = Slate, fontSize = 8.sp)
                }
            }
        }

        // ── High risk advice ──────────────────────────────────────────────
        AnimatedVisibility(
            visible = state.alertLevel.ordinal >= AlertLevel.ELEVATED.ordinal,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            val advice = when (state.alertLevel) {
                AlertLevel.CRITICAL -> "🚨 CRITICAL RISK — Do NOT share OTP, card number, or banking details. Hang up immediately."
                AlertLevel.ELEVATED -> "⚠ HIGH RISK — This call shows signs of fraud. Do not act on urgent requests."
                else -> ""
            }
            if (advice.isNotBlank()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(color.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                        .border(BorderStroke(1.dp, color), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Text(advice, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 17.sp)
                }
            }
        }

        // ── Footer ────────────────────────────────────────────────────────
        Text(
            "🛡 On-device analysis only. No audio leaves this phone.",
            color = SafeGreen,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── Shared sub-composables ────────────────────────────────────────────────────

@Composable
private fun IntelligenceSection(
    emoji: String,
    title: String,
    color: Color,
    content: @Composable () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.3f)), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("$emoji $title", color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        content()
    }
}

@Composable
private fun LabelRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Slate, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(72.dp))
        Text(value, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RiskBar(score: Int, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFFE2E8F0))
    ) {
        Box(
            Modifier
                .fillMaxWidth(score / 100f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}

// Extension to allow >= comparison on enum by ordinal
private operator fun AlertLevel.compareTo(other: AlertLevel): Int = ordinal - other.ordinal
