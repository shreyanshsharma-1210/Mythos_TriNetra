package com.trustmesh.app.vcd.ui.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trustmesh.app.vcd.VcdApp
import com.trustmesh.app.vcd.audio.MicCapture
import com.trustmesh.app.vcd.pipeline.Fusion
import com.trustmesh.app.vcd.pipeline.FusionThresholds
import com.trustmesh.app.vcd.pipeline.Level
import com.trustmesh.app.vcd.service.LiveSession
import com.trustmesh.app.vcd.service.LiveVerificationService
import com.trustmesh.app.vcd.ui.components.CriticalAlert
import com.trustmesh.app.vcd.ui.components.DisclosureBanner
import com.trustmesh.app.vcd.ui.components.LevelMeter
import com.trustmesh.app.vcd.ui.components.ScorePanel
import com.trustmesh.app.vcd.ui.components.StatusIndicator
import com.trustmesh.app.vcd.ui.components.reasonDetail
import com.trustmesh.app.vcd.ui.permission.rememberMicPermissionState
import com.trustmesh.app.vcd.ui.theme.StatusColors

/**
 * Live Verification.
 *
 * Structural rule, and the reason the banner is the first child rather than a decoration: the
 * banner composable is what opens [com.mythos.vcd.service.DisclosureGate], and the service will
 * not open the microphone unless that gate is open. The disclosure is therefore not something the
 * screen remembers to show — it is the thing that makes capture possible at all.
 */
@Composable
fun LiveVerificationScreen(app: VcdApp, contactId: Long?, onBack: () -> Unit) {
    val context = LocalContext.current
    val permission = rememberMicPermissionState()
    val session by LiveSession.state.collectAsStateWithLifecycle()
    val thresholds = remember { FusionThresholds.PROVISIONAL }

    var criticalDismissed by remember { mutableStateOf(false) }

    val contact by produceState<String?>(initialValue = null, contactId) {
        value = contactId?.let { app.contacts.get(it)?.name }
    }

    // Stop capture whenever this screen goes away, for any reason. Leaving a microphone open
    // behind a screen the user has navigated away from is precisely the behaviour this app
    // exists to argue against.
    DisposableEffect(Unit) {
        onDispose { LiveVerificationService.stop(context) }
    }

    BackHandler(enabled = session.running) {
        LiveVerificationService.stop(context)
        onBack()
    }

    // The stabilised peak, not the raw one. Latching the takeover on a single window meant one
    // noisy frame produced a permanent clone accusation for the rest of the session.
    val criticalVerdict = session.scores.stablePeakVerdict
        ?.takeIf { session.scores.stablePeakLevel == Level.CRITICAL && !criticalDismissed }

    // The banner sits outside the critical/normal branch on purpose. Rendering the full-screen
    // alert in place of the banner would take the disclosure off screen while the microphone was
    // still open — and "Keep listening" leaves it open. The alert stacks under the banner instead,
    // so there is no state in which capture is running without the disclosure visible.
    Column(Modifier.fillMaxSize()) {
        if (session.running) {
            DisclosureBanner(
                subtitle = "Microphone is open · the call must be on speakerphone",
            )
        }

        if (criticalVerdict != null) {
            CriticalAlert(
                verdict = criticalVerdict,
                thresholds = thresholds,
                onDismiss = { criticalDismissed = true },
                onStopVerifying = {
                    LiveVerificationService.stop(context)
                    criticalDismissed = true
                },
            )
            return@Column
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (session.running) "Screening this call" else "Live Verification",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                contact?.let { "Checking against $it" }
                    ?: "No contact selected — only the synthetic-speech check will run.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )

            session.fatalError?.let { error -> FailureCard(error) }

            if (!permission.granted) {
                PermissionMissingCard(onRequest = permission::request)
            } else if (!session.running) {
                StartCard(
                    onStart = {
                        criticalDismissed = false
                        LiveVerificationService.start(context, contactId, contact)
                    }
                )
            }

            if (session.running || session.scores.hasAnyMeasurement) {
                StatusIndicator(
                    level = session.scores.stableLevel,
                    verdict = session.scores.stableVerdict,
                    subtitle = if (session.scores.stillListening) {
                        "Listening — a few seconds of speech are needed before anything is claimed."
                    } else {
                        session.scores.stableVerdict?.reason?.let { reasonDetail(it) }
                    },
                )

                ScorePanel(
                    similarity = session.scores.smoothedSimilarity,
                    synthetic = session.scores.smoothedSynthetic,
                    thresholds = thresholds,
                    spoofCheck = Fusion.spoofCheckStatus(session.baselineSynthetic, thresholds)
                        .takeIf { session.contactId != null } ?: Fusion.SpoofCheck.USABLE,
                    baselineSynthetic = session.baselineSynthetic,
                )

                LevelMeter(
                    rms = session.mic.rms,
                    peak = session.mic.peak,
                    active = session.running && session.mic.failure == null,
                )

                DiagnosticsCard(session)
            }

            if (session.running) {
                Button(
                    onClick = {
                        LiveVerificationService.stop(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = StatusColors.critical),
                ) { Text("Stop and close the microphone") }
            }

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StartCard(onStart: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Before you start", fontWeight = FontWeight.Bold)
            Text(
                "Put the call on speakerphone. This app listens through the ordinary microphone " +
                    "and cannot hear a call held to your ear — it has no access to the cellular " +
                    "audio stream, by design.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "A red banner will appear at the top for the whole time the microphone is open.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text("Start listening")
            }
        }
    }
}

@Composable
private fun PermissionMissingCard(onRequest: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = StatusColors.suspiciousContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Microphone access needed", fontWeight = FontWeight.Bold, color = StatusColors.suspicious)
            Text(
                "Live verification cannot run without it. Test Mode still works — it analyses " +
                    "audio files and never touches the microphone.",
                color = StatusColors.suspicious,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRequest) { Text("Allow microphone") }
        }
    }
}

@Composable
private fun FailureCard(error: String) {
    Card(colors = CardDefaults.cardColors(containerColor = StatusColors.criticalContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Verification stopped", fontWeight = FontWeight.Bold, color = StatusColors.critical)
            Text(error, color = StatusColors.critical, style = MaterialTheme.typography.bodyMedium)
            Text(
                "No score is shown for audio the app is not confident it captured.",
                color = StatusColors.critical.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * The evidence panel. It exists so a tester can tell a real measurement from a plausible-looking
 * one — window counts, the audio mode the OS was in, and how quickly the banner appeared relative
 * to the microphone opening.
 */
@Composable
private fun DiagnosticsCard(session: LiveSession.State) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Capture evidence", fontWeight = FontWeight.Bold)
            DiagRow("Windows measured", "${session.scores.measuredWindows}")
            DiagRow("Windows skipped (too quiet)", "${session.scores.skippedWindows}")
            DiagRow(
                "Banner shown before mic opened",
                session.bannerToCaptureMs?.let {
                    if (it >= 0) "yes, ${it} ms earlier" else "NO — interlock failure"
                } ?: "not yet",
            )
            DiagRow("Audio mode", MicCapture.audioModeName(session.mic.audioMode))
            DiagRow("Speakerphone", if (session.mic.speakerphoneOn) "on" else "off / unknown")
            DiagRow("Samples captured", "${session.mic.samplesCaptured}")
            session.scores.latest?.let {
                DiagRow("Last inference", "${it.inferenceMillis} ms")
                DiagRow("Window RMS", "%.4f".format(it.rms))
            }
            if (!session.mic.speakerphoneOn && session.running) {
                Text(
                    "Speakerphone does not appear to be on. Without it the microphone cannot " +
                        "hear the other party at all, and any score here would be measuring your " +
                        "own voice.",
                    color = StatusColors.suspicious,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}
