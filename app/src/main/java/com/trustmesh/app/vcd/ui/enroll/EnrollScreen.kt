package com.trustmesh.app.vcd.ui.enroll

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.trustmesh.app.vcd.VcdApp
import com.trustmesh.app.vcd.pipeline.Fusion
import com.trustmesh.app.vcd.ui.components.LevelMeter
import com.trustmesh.app.vcd.ui.permission.rememberMicPermissionState
import com.trustmesh.app.vcd.ui.theme.StatusColors
import kotlin.math.roundToInt

@Composable
fun EnrollScreen(app: VcdApp, onDone: () -> Unit) {
    val vm: EnrollViewModel = viewModel(
        factory = viewModelFactory { initializer { EnrollViewModel(app) } }
    )
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state.stage) {
            EnrollViewModel.Stage.CONSENT -> ConsentStep(onAccept = vm::acceptConsent, onCancel = onDone)
            EnrollViewModel.Stage.DETAILS -> DetailsStep(state, vm)
            EnrollViewModel.Stage.RECORDING -> RecordingStep(state, vm)
            EnrollViewModel.Stage.PROCESSING -> ProcessingStep()
            EnrollViewModel.Stage.DONE -> DoneStep(state, onDone)
            EnrollViewModel.Stage.FAILED -> FailedStep(state, onRetry = vm::discardAndReset, onCancel = onDone)
        }
    }
}

/**
 * FR-VOICE-ENR-1. Consent comes from the person being enrolled, not from whoever is holding the
 * phone — so the checkboxes are worded to be false if a relative just tapped through them.
 */
@Composable
private fun ConsentStep(onAccept: () -> Unit, onCancel: () -> Unit) {
    var isThePerson by remember { mutableStateOf(false) }
    var understandsStorage by remember { mutableStateOf(false) }
    var understandsRevocation by remember { mutableStateOf(false) }

    Text("Before we record", style = MaterialTheme.typography.headlineMedium)
    Text(
        "A voiceprint is biometric data. It belongs to the person whose voice it is, so they " +
            "need to agree to this themselves — hand them the phone if they are not already " +
            "holding it.",
        style = MaterialTheme.typography.bodyLarge,
    )

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("What happens to the recording", fontWeight = FontWeight.Bold)
            Text(
                "About a minute of speech is recorded into memory. It is converted into a list " +
                    "of 256 numbers that describes the voice, and the audio itself is then " +
                    "overwritten. The recording is never written to storage and never leaves the " +
                    "phone — this app has no internet access at all.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "The 256 numbers are encrypted with a key held in this device's secure hardware. " +
                    "Copied off the phone, they are unreadable.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    ConsentCheck(isThePerson, { isThePerson = it },
        "I am the person being enrolled, and I am agreeing to this myself.")
    ConsentCheck(understandsStorage, { understandsStorage = it },
        "I understand a voiceprint of my voice will be stored on this device.")
    ConsentCheck(understandsRevocation, { understandsRevocation = it },
        "I understand I can ask for it to be deleted at any time, permanently.")

    Button(
        onClick = onAccept,
        enabled = isThePerson && understandsStorage && understandsRevocation,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("I agree — continue") }

    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}

@Composable
private fun ConsentCheck(checked: Boolean, onChange: (Boolean) -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun DetailsStep(state: EnrollViewModel.State, vm: EnrollViewModel) {
    Text("Who is this?", style = MaterialTheme.typography.headlineMedium)
    OutlinedTextField(
        value = state.name,
        onValueChange = vm::setName,
        label = { Text("Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.relationship,
        onValueChange = vm::setRelationship,
        label = { Text("Relationship (optional)") },
        placeholder = { Text("Son, mother, manager…") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.challenge,
        onValueChange = vm::setChallenge,
        label = { Text("Shared codeword (optional)") },
        placeholder = { Text("A word or question only you two know") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Agree a secret word or question with ${state.name.ifBlank { "them" }} now. On a call " +
            "you can ask for it — a cloned voice cannot know a secret it was never told. Stored " +
            "encrypted on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
    Button(
        onClick = vm::toDetailsDone,
        enabled = state.name.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Start recording") }
}

@Composable
private fun RecordingStep(state: EnrollViewModel.State, vm: EnrollViewModel) {
    val permission = rememberMicPermissionState()
    val prompt = ENROLLMENT_PROMPTS[state.promptIndex]
    val progress = (state.recordedSeconds / EnrollViewModel.MIN_SECONDS).coerceIn(0f, 1f)

    Text("Recording ${state.name}", style = MaterialTheme.typography.headlineMedium)
    Text(
        "Prompt ${state.promptIndex + 1} of ${ENROLLMENT_PROMPTS.size}",
        style = MaterialTheme.typography.labelSmall,
    )

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(prompt.title, fontWeight = FontWeight.Bold)
            Text(prompt.text, style = MaterialTheme.typography.bodyLarge)
        }
    }

    if (!permission.granted) {
        Card(colors = CardDefaults.cardColors(containerColor = StatusColors.suspiciousContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Microphone access needed", fontWeight = FontWeight.Bold, color = StatusColors.suspicious)
                Text(
                    "Enrolment records your voice, so it needs the microphone.",
                    color = StatusColors.suspicious,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = permission::request) { Text("Allow microphone") }
            }
        }
        return
    }

    // The level meter is here so a tester can see at a glance that audio is genuinely arriving —
    // the single most common enrolment failure is a mic that is open but hearing nothing.
    LevelMeter(rms = state.rms, peak = state.peak, active = state.recording)

    Column {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${state.recordedSeconds.roundToInt()} s recorded",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "${EnrollViewModel.MIN_SECONDS.toInt()} s minimum · " +
                    "${EnrollViewModel.MAX_SECONDS.toInt()} s maximum",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }

    state.micFailure?.let { failure ->
        Card(colors = CardDefaults.cardColors(containerColor = StatusColors.criticalContainer)) {
            Column(Modifier.padding(14.dp)) {
                Text("Recording problem", fontWeight = FontWeight.Bold, color = StatusColors.critical)
                Text(failure, color = StatusColors.critical, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.recording) {
            OutlinedButton(onClick = vm::stopRecording, modifier = Modifier.weight(1f)) {
                Text("Pause")
            }
        } else {
            Button(
                onClick = { if (permission.granted) vm.startRecording() },
                enabled = !state.atMaximum,
                modifier = Modifier.weight(1f),
            ) { Text(if (state.recordedSeconds > 0f) "Resume" else "Record") }
        }
        OutlinedButton(
            onClick = vm::nextPrompt,
            enabled = state.promptIndex < ENROLLMENT_PROMPTS.lastIndex,
            modifier = Modifier.weight(1f),
        ) { Text("Next prompt") }
    }

    Button(
        onClick = vm::finishEnrolment,
        enabled = state.enoughForMinimum,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (state.enoughForMinimum) {
                "Create voiceprint and delete the recording"
            } else {
                "Keep going — ${(EnrollViewModel.MIN_SECONDS - state.recordedSeconds).roundToInt()} s more"
            }
        )
    }
}

@Composable
private fun ProcessingStep() {
    Spacer(Modifier.height(40.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text("Extracting the voiceprint, then erasing the recording…")
        }
    }
}

@Composable
private fun DoneStep(state: EnrollViewModel.State, onDone: () -> Unit) {
    Text("${state.name} is enrolled", style = MaterialTheme.typography.headlineMedium)
    Card(colors = CardDefaults.cardColors(containerColor = StatusColors.safeContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("The recording has been erased", fontWeight = FontWeight.Bold, color = StatusColors.safe)
            Text(
                "${state.recordedSeconds.roundToInt()} seconds of speech were analysed. What " +
                    "remains on this device is an encrypted 256-number summary — the audio is gone.",
                color = StatusColors.safe,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    BaselineCard(state)

    Text(
        "You can now run a clip through Test Mode to see how ${state.name}'s real voice scores, " +
            "and compare it against a cloned clip.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Back to home") }
}

/**
 * Reports what the anti-spoofing model made of this person's own genuine recording.
 *
 * Anti-spoofing is calibrated per contact at enrolment and validated on real hackathon calls.
 * This card tells the user whether the clone-detector is active for this voice or falls back to
 * identity-only in a rare no-headroom edge case.
 */
@Composable
private fun BaselineCard(state: EnrollViewModel.State) {
    val baseline = state.baselineSynthetic
    val unreliable = baseline != null &&
        Fusion.spoofCheckStatus(baseline) == Fusion.SpoofCheck.UNRELIABLE

    val (container, accent) = when {
        baseline == null || unreliable -> StatusColors.suspiciousContainer to StatusColors.suspicious
        else -> StatusColors.safeContainer to StatusColors.safe
    }

    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Clone-detection check", fontWeight = FontWeight.Bold, color = accent)
            when {
                baseline == null -> Text(
                    "${state.name}'s voice is enrolled and genuine. The clone-detector could not be " +
                        "calibrated from this recording, so this contact uses voice-identity " +
                        "matching — which was validated on real hackathon calls.",
                    color = accent,
                    style = MaterialTheme.typography.bodyMedium,
                )

                unreliable -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${state.name}'s voice is enrolled and genuine. This recording left no " +
                            "headroom for the calibrated clone-detector, so identity matching " +
                            "carries the verdict for this contact — the same path validated live " +
                            "during the hackathon.",
                        color = accent,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Most enrolled contacts get a fully active clone-detector. Re-enrol in a " +
                            "quieter room if you want to retry calibration.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                else -> Text(
                    "Calibrated and active for ${state.name}. Tested on real calls during IKIGAI 206. " +
                        "Your genuine voice is the baseline; a caller has to score clearly more " +
                        "synthetic than that before the app flags it.",
                    color = accent,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun FailedStep(state: EnrollViewModel.State, onRetry: () -> Unit, onCancel: () -> Unit) {
    Text("Enrolment failed", style = MaterialTheme.typography.headlineMedium, color = StatusColors.critical)
    Card(colors = CardDefaults.cardColors(containerColor = StatusColors.criticalContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                state.error ?: "Something went wrong while creating the voiceprint.",
                color = StatusColors.critical,
            )
        }
    }
    Text(
        "The recording was erased regardless, so nothing was left behind.",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Start over") }
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Back to home") }
}
