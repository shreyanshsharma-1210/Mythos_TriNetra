package com.mythos.vcd.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythos.vcd.pipeline.Fusion
import com.mythos.vcd.pipeline.FusionThresholds
import com.mythos.vcd.pipeline.Level
import com.mythos.vcd.pipeline.Reason
import com.mythos.vcd.pipeline.Verdict
import com.mythos.vcd.ui.theme.StatusColors

/**
 * The headline status block. Phase 5's escalation ladder lives here:
 * a quiet badge at SAFE, a filled banner at SUSPICIOUS, and a pulsing block at CRITICAL that the
 * caller-facing full-screen alert then takes over from.
 */
@Composable
fun StatusIndicator(
    level: Level,
    verdict: Verdict?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val accent by animateColorAsState(StatusColors.accent(level), label = "accent")
    val container by animateColorAsState(StatusColors.container(level), label = "container")

    val pulse = rememberInfiniteTransition(label = "status-pulse")
    val alpha by pulse.animateFloat(
        initialValue = if (level == Level.CRITICAL) 0.55f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha",
    )

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .padding(20.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "Status: ${StatusColors.label(level)}. " +
                    (verdict?.headline() ?: "")
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                imageVector = levelIcon(level),
                contentDescription = null,
                tint = accent.copy(alpha = if (level == Level.CRITICAL) alpha else 1f),
                modifier = Modifier.height(34.dp),
            )
            Text(
                text = StatusColors.label(level),
                color = accent.copy(alpha = if (level == Level.CRITICAL) alpha else 1f),
                fontWeight = FontWeight.Black,
                fontSize = 34.sp,
                letterSpacing = (-0.5).sp,
            )
        }
        verdict?.let {
            Text(it.headline(), color = accent, style = MaterialTheme.typography.bodyLarge)
        }
        subtitle?.let {
            Text(it, color = accent.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun levelIcon(level: Level) = when (level) {
    Level.SAFE -> Icons.Filled.CheckCircle
    Level.SUSPICIOUS -> Icons.Filled.Warning
    Level.CRITICAL -> Icons.Filled.ReportProblem
    Level.INDETERMINATE -> Icons.AutoMirrored.Filled.HelpOutline
}

/**
 * The two scores side by side, with their thresholds drawn in.
 *
 * They are shown as two separate numbers rather than one combined figure on purpose. The whole
 * argument of this module is that similarity and synthetic-probability answer different questions,
 * and collapsing them into a single percentage would throw away the exact distinction that catches
 * a clone.
 */
@Composable
fun ScorePanel(
    similarity: Float?,
    synthetic: Float?,
    thresholds: FusionThresholds,
    modifier: Modifier = Modifier,
    spoofCheck: Fusion.SpoofCheck = Fusion.SpoofCheck.USABLE,
    baselineSynthetic: Float? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ScoreBar(
            label = "VOICE SIMILARITY",
            value = similarity,
            threshold = thresholds.similarityHigh,
            thresholdLabel = "match threshold ${"%.2f".format(thresholds.similarityHigh)} · " +
                "cosine distance to the enrolled voiceprint",
            highIsBad = false,
        )
        val effective = Fusion.effectiveSyntheticThreshold(baselineSynthetic, thresholds)
        ScoreBar(
            label = "SYNTHETIC PROBABILITY",
            value = synthetic,
            threshold = effective,
            thresholdLabel = when (spoofCheck) {
                Fusion.SpoofCheck.UNRELIABLE -> "not in use for this contact · shown for reference only"
                Fusion.SpoofCheck.NO_BASELINE ->
                    "alert threshold ${"%.2f".format(effective)} · uncalibrated for this voice"
                Fusion.SpoofCheck.USABLE ->
                    "alert threshold ${"%.2f".format(effective)} · " +
                        (baselineSynthetic
                            ?.let { "raised from ${"%.2f".format(thresholds.syntheticHigh)} by this contact's own baseline of ${"%.2f".format(it)}" }
                            ?: "computed independently of who is speaking")
            },
            highIsBad = true,
        )

        // A number the app is not acting on must not be displayed as though it were. These two
        // notes are the difference between "the check ran and passed" and "the check did not run".
        SpoofCheckNote(spoofCheck, baselineSynthetic)

        if (!thresholds.calibrated) {
            Text(
                "Thresholds are provisional starting points, not measured operating points. " +
                    "Calibrate them against your own real and cloned clips in Test Mode before " +
                    "reading anything into a borderline score.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * Says out loud how much the synthetic-probability number is worth for this contact.
 *
 * This is not a disclaimer for its own sake. The anti-spoofing checkpoint was measured returning
 * ~0.999 on known-genuine recordings of a real person, so "0.99" on screen can mean either "this is
 * a clone" or "this model does not understand this voice", and the two are indistinguishable from
 * the number alone. The baseline taken at enrolment is what tells them apart, and if it is missing
 * or useless the user is entitled to know that rather than reading the bar at face value.
 */
@Composable
private fun SpoofCheckNote(spoofCheck: Fusion.SpoofCheck, baselineSynthetic: Float?) {
    val text = when (spoofCheck) {
        Fusion.SpoofCheck.USABLE -> return
        Fusion.SpoofCheck.UNRELIABLE ->
            "The computer-generation check is switched off for this contact. At enrolment it " +
                "scored their own consented recording at " +
                "${baselineSynthetic?.let { "%.2f".format(it) } ?: "the top of the scale"}, so it " +
                "cannot tell a clone of them from the real thing. This result rests on voice " +
                "matching alone."
        Fusion.SpoofCheck.NO_BASELINE ->
            "The computer-generation check has not been calibrated for this voice. It is still " +
                "running against the general threshold, but it has not been checked against a " +
                "known-genuine recording of this person, so a high score here is weaker evidence " +
                "than it looks. Re-enrol this contact to calibrate it."
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = StatusColors.accent(Level.SUSPICIOUS),
    )
}

/**
 * FR-VOICE-ALT-1 / ALT-2 — the full-screen CRITICAL takeover.
 *
 * It names both scores in plain language rather than showing a risk percentage, because the user's
 * actual decision is "do I act on what this caller is asking for", and "sounds like them but looks
 * computer-generated" is the sentence that informs that decision.
 */
@Composable
fun CriticalAlert(
    verdict: Verdict,
    thresholds: FusionThresholds,
    onDismiss: () -> Unit,
    onStopVerifying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = rememberInfiniteTransition(label = "critical")
    val glow by pulse.animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "glow",
    )

    Column(
        modifier
            .fillMaxSize()
            .background(StatusColors.critical.copy(alpha = glow))
            .verticalScroll(rememberScrollState())
            .padding(28.dp)
            .semantics {
                liveRegion = LiveRegionMode.Assertive
                contentDescription = "Critical alert. ${verdict.headline()}"
            },
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(40.dp))

        Icon(
            Icons.Filled.ReportProblem,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.height(56.dp),
        )

        Text(
            "POSSIBLE CLONED VOICE",
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 34.sp,
            lineHeight = 38.sp,
        )

        Text(
            verdict.headline(),
            color = Color.White,
            fontSize = 20.sp,
            lineHeight = 27.sp,
        )

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.14f))
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AlertScoreLine(
                    "Sounds like ${verdict.contactName ?: "the enrolled contact"}",
                    verdict.voiceSimilarity,
                    "match threshold ${"%.2f".format(thresholds.similarityHigh)}",
                )
                AlertScoreLine(
                    "Looks computer-generated",
                    verdict.syntheticProbability,
                    "alert threshold ${"%.2f".format(thresholds.syntheticHigh)}",
                )
                Text(
                    "Those two together are the pattern this app watches for. A convincing clone " +
                        "is built to sound like the real person — so sounding right is not, on " +
                        "its own, evidence that it is them.",
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.14f))
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What to do", color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    "Do not send money or share codes based on this call. Hang up and call " +
                        "${verdict.contactName ?: "the person"} back on the number you already " +
                        "have for them, or ask something only they would know.",
                    color = Color.White.copy(alpha = 0.92f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Text(
            "This is an automated signal, not proof. It can be wrong in both directions.",
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Start,
        )

        Button(
            onClick = onStopVerifying,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = StatusColors.critical,
            ),
        ) { Text("Stop verifying and close the microphone") }

        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        ) { Text("Keep listening") }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AlertScoreLine(label: String, value: Float?, thresholdText: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(
                thresholdText,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            value?.let { "%.2f".format(it) } ?: "—",
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 26.sp,
        )
    }
}

/** Short explanation of what a reason code means, for the details section under a verdict. */
fun reasonDetail(reason: Reason): String = when (reason) {
    Reason.MATCH_AUTHENTIC ->
        "The voice matches the enrolled voiceprint, and the anti-spoofing model found no strong " +
            "evidence of synthesis."
    Reason.CLONE_SIGNATURE ->
        "High similarity together with high synthetic probability. Treated as the most severe " +
            "result rather than averaged into something milder."
    Reason.POSSIBLE_SYNTHESIS ->
        "The voice matches, but synthetic probability is above the quiet band. Could be a weak " +
            "clone, or could be compression and line noise on a real call."
    Reason.NOT_CLAIMED_CONTACT ->
        "Similarity is below the match threshold, so this is decided on identity alone, whatever " +
            "the synthetic score says."
    Reason.BORDERLINE_SIMILARITY ->
        "Similarity sits between the two thresholds. Common with short, noisy, or heavily " +
            "compressed audio."
    Reason.SYNTHETIC_UNKNOWN_SPEAKER ->
        "Synthetic probability is high, but no contact was selected, so nothing was checked " +
            "against a voiceprint."
    Reason.NO_VOICEPRINT_SELECTED ->
        "No contact was selected. Only the anti-spoofing half of the pipeline ran."
    Reason.MATCH_SPOOF_CHECK_UNRELIABLE ->
        "The anti-spoofing model scored this contact's own consented enrolment recording as " +
            "synthetic, so it cannot tell a clone from them and its output is not used. Identity " +
            "matching still works and is what this result is based on."
    Reason.NO_SPEECH ->
        "The window was below the level where measuring anything would be meaningful."
    Reason.PIPELINE_UNAVAILABLE ->
        "A model or the capture path failed, so no score was produced for this window."
}
