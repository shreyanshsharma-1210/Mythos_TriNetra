package com.mythos.vcd.ui.spike

import android.media.AudioManager
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythos.vcd.audio.AudioConstants
import com.mythos.vcd.audio.MicCapture
import com.mythos.vcd.ui.components.DisclosureBanner
import com.mythos.vcd.ui.components.LevelMeter
import com.mythos.vcd.ui.permission.rememberMicPermissionState
import com.mythos.vcd.ui.theme.StatusColors
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Phase 0 — the capture-feasibility spike, shipped as a screen rather than a throwaway branch.
 *
 * The question it answers cannot be answered anywhere but on a real handset in a real call:
 * whether AudioRecord on MediaRecorder.AudioSource.MIC still returns usable samples while
 * AudioManager is in MODE_IN_CALL. Behaviour here is OEM-specific — some builds hand the app
 * silence, some attenuate heavily, some work fine — and no emulator reproduces any of it.
 *
 * So this screen measures rather than asserts: live level, the audio mode the OS reports, whether
 * the platform says our input is being silenced, and how much of the last ten seconds was
 * non-silent. It reaches a verdict you can screenshot.
 */
@Composable
fun CaptureSpikeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val permission = rememberMicPermissionState()
    val mic = remember { MicCapture(context) }
    val state by mic.state.collectAsStateWithLifecycle()

    var voicedChunks by remember { mutableStateOf(0) }
    var totalChunks by remember { mutableStateOf(0) }
    var peakSeen by remember { mutableStateOf(0f) }

    DisposableEffect(Unit) {
        onDispose { mic.stop() }
    }

    // Poll routing while running, since the user will be flipping speakerphone on and off and the
    // whole point is to watch what that does.
    LaunchedEffect(state.running) {
        while (state.running) {
            mic.refreshRouting()
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (state.running) {
            DisclosureBanner(subtitle = "Diagnostics only — nothing is being analysed or stored")
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text("Capture diagnostics", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Answers one question: can this handset let an app hear the other party through " +
                    "the microphone during a speakerphone call?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("How to run this test", fontWeight = FontWeight.Bold)
                    Text("1 · Call someone, or have them call you.", style = MaterialTheme.typography.bodyMedium)
                    Text("2 · Put the call on speakerphone.", style = MaterialTheme.typography.bodyMedium)
                    Text("3 · Come back here and tap Start.", style = MaterialTheme.typography.bodyMedium)
                    Text("4 · Ask them to talk while you stay silent. Watch the meter.",
                        style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "If the meter moves only when you speak and not when they do, this device " +
                            "does not route call audio to the mic and live verification will not " +
                            "work on it. Test Mode is unaffected either way.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (!permission.granted) {
                Card(colors = CardDefaults.cardColors(containerColor = StatusColors.suspiciousContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Microphone access needed", fontWeight = FontWeight.Bold, color = StatusColors.suspicious)
                        Button(onClick = permission::request) { Text("Allow microphone") }
                    }
                }
            } else {
                Button(
                    onClick = {
                        if (state.running) {
                            mic.stop()
                        } else {
                            voicedChunks = 0
                            totalChunks = 0
                            peakSeen = 0f
                            mic.start { samples, length ->
                                totalChunks++
                                var p = 0f
                                for (i in 0 until length) {
                                    val a = if (samples[i] < 0f) -samples[i] else samples[i]
                                    if (a > p) p = a
                                }
                                if (p > VOICED_THRESHOLD) voicedChunks++
                                peakSeen = max(peakSeen, p)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (state.running) {
                        ButtonDefaults.buttonColors(containerColor = StatusColors.critical)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) { Text(if (state.running) "Stop" else "Start capture test") }
            }

            LevelMeter(rms = state.rms, peak = state.peak, active = state.running)

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("What the platform reports", fontWeight = FontWeight.Bold)
                    Diag("Capture running", if (state.running) "yes" else "no")
                    Diag("Audio source", "MediaRecorder.AudioSource.MIC")
                    Diag("Format", "${AudioConstants.SAMPLE_RATE} Hz · mono · PCM 16-bit")
                    Diag("AudioManager.getMode()", MicCapture.audioModeName(state.audioMode))
                    Diag("Routed to speaker", if (state.speakerphoneOn) "yes" else "no / unknown")
                    state.routedDevice?.let { Diag("Communication device", it) }
                    Diag("System says input silenced", if (state.systemSilenced) "YES" else "no")
                    Diag("Samples captured", "${state.samplesCaptured}")
                    Diag(
                        "Non-silent chunks",
                        if (totalChunks == 0) "—"
                        else "$voicedChunks / $totalChunks (${(100 * voicedChunks / totalChunks)}%)",
                    )
                    Diag("Peak amplitude seen", "%.4f".format(peakSeen))
                }
            }

            Verdict(state = state, totalChunks = totalChunks, voicedChunks = voicedChunks)

            state.detail?.let { detail ->
                Card(colors = CardDefaults.cardColors(containerColor = StatusColors.criticalContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Platform reported", fontWeight = FontWeight.Bold, color = StatusColors.critical)
                        Text(detail, color = StatusColors.critical, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Verdict(state: MicCapture.State, totalChunks: Int, voicedChunks: Int) {
    val inCall = state.audioMode == AudioManager.MODE_IN_CALL ||
        state.audioMode == AudioManager.MODE_IN_COMMUNICATION

    val (title, body, color) = when {
        !state.running && totalChunks == 0 ->
            Triple("Not tested yet", "Start a call, put it on speaker, then tap Start.", StatusColors.indeterminate)

        state.failure != null ->
            Triple(
                "Capture failed",
                "The microphone did not produce usable audio: ${state.failure}. Live verification " +
                    "will not work on this device under these conditions. Test Mode still will.",
                StatusColors.critical,
            )

        totalChunks < 20 ->
            Triple("Keep going", "Not enough audio yet — let it run for a few more seconds.", StatusColors.indeterminate)

        !inCall ->
            Triple(
                "Microphone works, but no call was active",
                "Audio is being captured cleanly, but AudioManager reported " +
                    "${MicCapture.audioModeName(state.audioMode)} rather than MODE_IN_CALL. This " +
                    "confirms the microphone path works; it does not yet answer the in-call " +
                    "question. Repeat during an actual speakerphone call.",
                StatusColors.suspicious,
            )

        voicedChunks * 100 / totalChunks < 5 ->
            Triple(
                "In a call, but hearing effectively nothing",
                "AudioRecord is running during ${MicCapture.audioModeName(state.audioMode)} but " +
                    "almost every chunk is silence. This device appears to mute or starve " +
                    "third-party mic capture during calls. Live verification is not viable here.",
                StatusColors.critical,
            )

        else ->
            Triple(
                "In-call capture appears to work",
                "AudioRecord returned non-silent audio during " +
                    "${MicCapture.audioModeName(state.audioMode)} for " +
                    "${voicedChunks * 100 / totalChunks}% of chunks. Confirm by ear that the meter " +
                    "moved when the other party spoke and you were silent — this counter cannot " +
                    "tell whose voice it is.",
                StatusColors.safe,
            )
    }

    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.14f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Verdict", style = MaterialTheme.typography.labelSmall, color = color)
            Text(title, fontWeight = FontWeight.Bold, color = color, style = MaterialTheme.typography.titleMedium)
            Text(body, color = color, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Diag(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

/** Peak amplitude above which a 100 ms chunk counts as containing something audible. */
private const val VOICED_THRESHOLD = 0.01f
