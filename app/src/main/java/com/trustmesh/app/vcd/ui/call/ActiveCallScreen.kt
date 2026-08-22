package com.trustmesh.app.vcd.ui.call

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trustmesh.app.vcd.pipeline.Fusion
import com.trustmesh.app.vcd.pipeline.FusionThresholds
import com.trustmesh.app.vcd.pipeline.Level
import com.trustmesh.app.vcd.ui.components.reasonDetail
import com.trustmesh.app.vcd.ui.theme.CallColors
import com.trustmesh.app.vcd.ui.theme.StatusColors
import com.trustmesh.app.vcd.voip.CallDiagnostics
import com.trustmesh.app.vcd.voip.CallManager
import com.trustmesh.app.vcd.voip.CallStage
import com.trustmesh.app.vcd.voip.CallState
import kotlinx.coroutines.delay

/**
 * The call screen.
 *
 * Laid out the way a call is: a full-bleed coloured field, a large avatar, the name, the state of
 * the call, and the controls at the bottom where a thumb reaches them. Everything the app knows
 * about verification is folded into one badge under the name rather than spread across cards,
 * because during a call a person has attention for one fact, not six.
 *
 * The badge shows the **stabilised** verdict, never the per-window one. A status that changes every
 * three seconds — SAFE, then SUSPICIOUS, then CRITICAL, while the same person talks — is worse than
 * no status: it trains the user to ignore it. See [com.trustmesh.app.vcd.pipeline.SessionScores].
 */
@Composable
fun ActiveCallScreen(state: CallState, onDismiss: () -> Unit) {
    val thresholds = remember { FusionThresholds.PROVISIONAL }
    var showDetail by remember { mutableStateOf(false) }

    // The duration comes from wall clock, so the screen needs a heartbeat to redraw it.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(state.stage) {
        while (state.stage == CallStage.CONNECTED) {
            delay(1000)
            tick++
        }
    }

    val connected = state.stage == CallStage.CONNECTED
    // With no saved voice there is nothing to verify against, so the screen must stay neutral rather
    // than turning red/green off a synthetic-only score. Verdicts require an enrolled contact.
    val level = if (connected && state.contactId != null) state.scores.stableLevel else Level.INDETERMINATE
    val verdict = state.scores.stableVerdict

    val top by animateColorAsState(
        when {
            !connected -> CallColors.brand
            level == Level.CRITICAL -> StatusColors.critical
            level == Level.SUSPICIOUS -> StatusColors.suspicious
            level == Level.SAFE -> StatusColors.safe
            else -> CallColors.brand
        },
        tween(600),
        label = "top",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, CallColors.brandDark))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))

            RingingHalo(state.stage == CallStage.INCOMING || state.stage == CallStage.RINGING_OUT) {
                Avatar(state.remoteName, size = 116)
            }

            Spacer(Modifier.height(20.dp))

            Text(
                state.remoteName ?: "Unknown",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(6.dp))

            Text(
                state.statusLine(),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 16.sp,
                fontFamily = if (connected) FontFamily.Monospace else FontFamily.Default,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "${state.remoteName ?: "Unknown"}. ${state.statusLine()}"
                },
            )

            Spacer(Modifier.height(22.dp))

            VerificationBadge(
                state = state,
                connected = connected,
                level = level,
                onClick = { showDetail = !showDetail },
            )

            // The shared-secret challenge: the one check a perfect clone cannot pass. Shown whenever
            // a codeword was set for this contact, because it works even when the models cannot.
            state.challenge?.takeIf { it.isNotBlank() }?.let { codeword ->
                Spacer(Modifier.height(14.dp))
                CodewordChallenge(codeword)
            }

            if (connected) {
                val intelState by com.trustmesh.app.callaudio.webrtc.WebRtcIntelligenceCoordinator.state.collectAsStateWithLifecycle()
                var isExpanded by remember { mutableStateOf(false) }

                LaunchedEffect(intelState.alertLevel) {
                    if (intelState.alertLevel.ordinal >= com.trustmesh.app.callaudio.webrtc.AlertLevel.ELEVATED.ordinal) {
                        isExpanded = true
                    }
                }

                Spacer(Modifier.height(14.dp))
                if (isExpanded) {
                    com.trustmesh.app.ui.screens.webrtccall.WebRtcCallOverlay(
                        state = intelState,
                        onCollapse = { isExpanded = false },
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                } else {
                    com.trustmesh.app.ui.screens.webrtccall.WebRtcCallPill(
                        state = intelState,
                        onExpand = { isExpanded = true },
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }

            if (showDetail && connected) {
                Spacer(Modifier.height(14.dp))
                DetailPanel(state, level, verdict, thresholds)
            }

            state.error?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    it,
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.25f))
                        .padding(14.dp),
                )
            }

            Spacer(Modifier.height(36.dp))
            CallControls(state, onDismiss)
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * The one thing worth a person's attention mid-call.
 *
 * Kept honest in three ways: it says "Checking voice…" rather than a level until there is enough
 * audio to mean it; the wording describes what was measured rather than blessing the person; and it
 * names the contact, because "verified" against nobody in particular would be meaningless.
 */
@Composable
private fun VerificationBadge(
    state: CallState,
    connected: Boolean,
    level: Level,
    onClick: () -> Unit,
) {
    val listening = connected && state.scores.stillListening
    val icon = when {
        !connected || listening -> Icons.AutoMirrored.Filled.HelpOutline
        level == Level.SAFE -> Icons.Filled.CheckCircle
        level == Level.SUSPICIOUS -> Icons.Filled.Warning
        level == Level.CRITICAL -> Icons.Filled.ReportProblem
        else -> Icons.AutoMirrored.Filled.HelpOutline
    }

    val headline = when {
        !connected && state.contactId != null -> "Will check against ${state.contactName}"
        !connected -> "No contact selected"
        // No saved voice takes precedence over "listening": there is nothing to compare the caller
        // against, so the app must not imply it is running an identity or clone check.
        state.contactId == null -> "No saved voice"
        listening -> "Checking voice…"
        else -> StatusColors.badge(level)
    }

    val sub = when {
        !connected && state.contactId != null -> "Voice protection ready"
        !connected -> "Identity will not be checked on this call"
        state.contactId == null -> "Identity and clone checks are off for this call"
        listening -> "Needs a few seconds of speech"
        level == Level.SAFE -> "Matches ${state.contactName}, no signs of synthesis"
        level == Level.SUSPICIOUS -> "Tap for what was measured"
        level == Level.CRITICAL -> "Do not act on this call. Tap for detail."
        else -> "Tap for detail"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.16f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (listening) {
            CircularProgressIndicator(
                Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
        } else {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(headline, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(sub, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
        }
        if (connected && !listening) {
            TextButton(onClick = onClick) {
                Text("Detail", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

/**
 * The shared-secret prompt. A cloned voice can sound identical and still not know a codeword agreed
 * in advance, so this is the app's most reliable check — and the only one that does not depend on a
 * model. Revealed on tap so a shoulder-surfer or a recording of the screen does not capture it.
 */
@Composable
private fun CodewordChallenge(codeword: String) {
    var revealed by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.16f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Ask for the codeword", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "The real person you enrolled knows this. A voice clone does not.",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp,
        )
        if (revealed) {
            Text(
                codeword,
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 22.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "They should say this back. If they can't, treat the call as unverified.",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
            )
        } else {
            TextButton(
                onClick = { revealed = true },
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            ) { Text("Reveal codeword") }
        }
    }
}

@Composable
private fun DetailPanel(
    state: CallState,
    level: Level,
    verdict: com.trustmesh.app.vcd.pipeline.Verdict?,
    thresholds: FusionThresholds,
) {
    val spoofCheck = if (state.contactId == null) {
        Fusion.SpoofCheck.USABLE
    } else {
        Fusion.spoofCheckStatus(state.baselineSynthetic, thresholds)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.22f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        verdict?.let {
            Text(it.headline(), color = Color.White, fontSize = 14.sp)
            Text(
                reasonDetail(it.reason),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
            )
        }

        ScoreLine(
            "Sounds like ${state.contactName ?: "the contact"}",
            state.scores.smoothedSimilarity,
            "match at ${"%.2f".format(thresholds.similarityHigh)}",
        )
        ScoreLine(
            "Looks computer-generated",
            state.scores.smoothedSynthetic,
            when (spoofCheck) {
                Fusion.SpoofCheck.UNRELIABLE -> "check off for this voice"
                Fusion.SpoofCheck.NO_BASELINE -> "uncalibrated for this voice"
                Fusion.SpoofCheck.USABLE ->
                    "alert at ${"%.2f".format(Fusion.effectiveSyntheticThreshold(state.baselineSynthetic, thresholds))}"
            },
        )

        if (spoofCheck != Fusion.SpoofCheck.USABLE) {
            Text(
                if (spoofCheck == Fusion.SpoofCheck.UNRELIABLE) {
                    "The computer-generation check is switched off for this contact: it scored " +
                        "their own consented recording as synthetic, so it cannot tell a clone of " +
                        "them from the real thing. This result rests on voice matching alone."
                } else {
                    "The computer-generation check has not been calibrated for this voice, so a " +
                        "high score here is weaker evidence than it looks."
                },
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
            )
        }

        if (!thresholds.calibrated) {
            Text(
                "Thresholds are provisional starting points, not measured operating points.",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 11.sp,
            )
        }

        DiagnosticsBlock(state.diagnostics)
    }
}

@Composable
private fun ScoreLine(label: String, value: Float?, note: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(note, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        }
        Text(
            value?.let { "%.2f".format(it) } ?: "—",
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 20.sp,
        )
    }
}

/**
 * The audio path, folded away behind the detail tap.
 *
 * Still here rather than deleted after the POC: the previous audio source returned silence with no
 * error at all, and a risk score shown without any way to see whether audio actually arrived can
 * repeat that failure invisibly.
 */
@Composable
private fun DiagnosticsBlock(d: CallDiagnostics) {
    if (!d.trackAttached) {
        Text(
            "No remote audio frames received yet.",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 11.sp,
        )
        return
    }
    if (d.silent) {
        Text(
            "Frames are arriving but all are digital silence — the other device may be muted.",
            color = Color.White,
            fontSize = 11.sp,
        )
    }
    Text(
        "${d.sampleRate} Hz · ${d.channels} ch · ${d.framesReceived} frames · " +
            "${d.windowsAnalysed} windows · ${d.lastInferenceMs} ms",
        color = Color.White.copy(alpha = 0.6f),
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
    )
}

/** A slow pulse behind the avatar while ringing, so the screen reads as live rather than stuck. */
@Composable
private fun RingingHalo(active: Boolean, content: @Composable () -> Unit) {
    if (!active) {
        content()
        return
    }
    val pulse = rememberInfiniteTransition(label = "ring")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.22f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "halo",
    )
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size((116 * scale).dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.18f))
        )
        content()
    }
}

@Composable
private fun CallControls(state: CallState, onDismiss: () -> Unit) {
    when (state.stage) {
        CallStage.INCOMING -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            RoundCallButton(Icons.Filled.CallEnd, "Decline", StatusColors.critical) {
                CallManager.decline()
            }
            RoundCallButton(Icons.Filled.Call, "Accept", StatusColors.safe) {
                CallManager.answer()
            }
        }

        CallStage.ENDED, CallStage.FAILED -> Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = CallColors.brandDark,
            ),
        ) { Text("Back to calls", fontWeight = FontWeight.Bold) }

        else -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToggleControl(
                    icon = if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = if (state.muted) "Unmute" else "Mute",
                    on = state.muted,
                    enabled = state.stage == CallStage.CONNECTED,
                ) { CallManager.setMuted(!state.muted) }

                ToggleControl(
                    icon = Icons.Filled.VolumeUp,
                    label = if (state.speakerphone) "Speaker" else "Earpiece",
                    on = state.speakerphone,
                    enabled = state.stage == CallStage.CONNECTED,
                ) { CallManager.setSpeakerphone(!state.speakerphone) }
            }

            RoundCallButton(Icons.Filled.CallEnd, "End", StatusColors.critical) {
                CallManager.dismiss()
            }
        }
    }
}

@Composable
private fun ToggleControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    on: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(62.dp),
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (on) Color.White else Color.White.copy(alpha = 0.18f),
                contentColor = if (on) CallColors.brandDark else Color.White,
                disabledContainerColor = Color.White.copy(alpha = 0.08f),
                disabledContentColor = Color.White.copy(alpha = 0.4f),
            ),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
        }
        Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
    }
}

@Composable
private fun RoundCallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    colour: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier.size(74.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = colour),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}
