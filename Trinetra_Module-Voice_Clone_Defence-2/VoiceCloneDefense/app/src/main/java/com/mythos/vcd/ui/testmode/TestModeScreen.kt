package com.mythos.vcd.ui.testmode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mythos.vcd.VcdApp
import com.mythos.vcd.pipeline.Fusion
import com.mythos.vcd.pipeline.Level
import com.mythos.vcd.ui.components.ScorePanel
import com.mythos.vcd.ui.components.StatusChip
import com.mythos.vcd.ui.components.StatusIndicator
import com.mythos.vcd.ui.components.reasonDetail
import com.mythos.vcd.ui.theme.StatusColors

@Composable
fun TestModeScreen(app: VcdApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: TestModeViewModel = viewModel(
        factory = viewModelFactory { initializer { TestModeViewModel(app) } }
    )
    val state by vm.state.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.pickFile(context, it) } }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Test Mode", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Runs an audio file through exactly the same pipeline a live call goes through. No " +
                "microphone, no call, no second device — and no microphone permission required.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1 · Choose a clip", fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick = {
                        picker.launch(
                            arrayOf("audio/*", "application/ogg", "video/mp4")
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(state.pickedName ?: "Pick an audio file") }
                Text(
                    "WAV, MP3, M4A, OGG, FLAC — anything this device can decode. Needs to be at " +
                        "least about 4 seconds long.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )

                Text("2 · Compare against", fontWeight = FontWeight.Bold)
                ContactPicker(state, vm::selectContact)

                Button(
                    onClick = { vm.run(context) },
                    enabled = state.pickedUri != null && !state.running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.running) "Analysing…" else "Run analysis") }

                if (state.running) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        state.error?.let { error ->
            Card(colors = CardDefaults.cardColors(containerColor = StatusColors.criticalContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Could not analyse that file", fontWeight = FontWeight.Bold, color = StatusColors.critical)
                    Text(error, color = StatusColors.critical, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        state.current?.let { run ->
            RunResult(run = run, vm = vm, isCurrent = true, context = context)
        }

        state.previous?.let { run ->
            Text(
                "Previous run — for side-by-side comparison",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            RunResult(run = run, vm = vm, isCurrent = false, context = context)
            TextButton(onClick = vm::clearComparison) { Text("Clear results") }
        }

        if (state.current == null && !state.running) {
            DemoProtocolCard()
        }

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ContactPicker(state: TestModeViewModel.State, onSelect: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.contacts.firstOrNull { it.id == state.selectedContactId }

    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.name ?: "No contact — synthetic-speech check only")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("No contact — synthetic-speech check only") },
                onClick = { onSelect(null); expanded = false },
            )
            state.contacts.filter { it.usableWithCurrentModel }.forEach { contact ->
                DropdownMenuItem(
                    text = { Text(contact.name) },
                    onClick = { onSelect(contact.id); expanded = false },
                )
            }
        }
    }
    if (state.contacts.isEmpty()) {
        Text(
            "No voices are enrolled yet, so only the synthetic-speech half of the pipeline can " +
                "run. Enrol someone to get a similarity score too.",
            style = MaterialTheme.typography.bodySmall,
            color = StatusColors.suspicious,
        )
    }
}

@Composable
private fun RunResult(
    run: TestModeViewModel.Run,
    vm: TestModeViewModel,
    isCurrent: Boolean,
    context: Context,
) {
    Card(
        colors = if (isCurrent) CardDefaults.cardColors()
        else CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(run.fileName, fontWeight = FontWeight.Bold)
                    Text(
                        buildString {
                            append("${run.scores.history.size} windows")
                            append(" · ${run.sourceSampleRate} Hz")
                            append(if (run.sourceChannels > 1) " · ${run.sourceChannels} ch" else " · mono")
                            append(" · ${run.elapsedMillis} ms")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                StatusChip(run.scores.peakLevel)
            }

            // The headline is the worst window, not the average. A clone that only shows itself in
            // part of a clip is still a clone, and averaging it away would be the same mistake as
            // averaging the two scores together.
            StatusIndicator(
                level = run.scores.peakLevel,
                verdict = run.scores.peakVerdict ?: run.scores.latest?.verdict,
                subtitle = (run.scores.peakVerdict ?: run.scores.latest?.verdict)
                    ?.reason?.let { reasonDetail(it) },
            )

            ScorePanel(
                similarity = run.medianSimilarity,
                synthetic = run.medianSynthetic,
                thresholds = vm.thresholds,
                spoofCheck = if (run.contactName == null) {
                    Fusion.SpoofCheck.USABLE
                } else {
                    Fusion.spoofCheckStatus(run.baselineSynthetic, vm.thresholds)
                },
                baselineSynthetic = run.baselineSynthetic,
            )
            Text(
                "Scores shown are the median across all windows. The status above reflects the " +
                    "most severe single window.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )

            WindowStrip(run)

            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(
                        ClipData.newPlainText("vcd-scores", vm.resultsAsCsv(run))
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Copy per-window scores as CSV") }
        }
    }
}

/** One cell per analysis window, so an intermittent detection is visible rather than averaged. */
@Composable
private fun WindowStrip(run: TestModeViewModel.Run) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Per-window results", style = MaterialTheme.typography.labelSmall)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            run.scores.history.forEach { a ->
                Box(
                    Modifier
                        .width(26.dp)
                        .height(34.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(StatusColors.accent(a.verdict.level).copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = a.verdict.syntheticProbability?.let { "%.0f".format(it * 100) } ?: "–",
                        style = MaterialTheme.typography.labelSmall,
                        color = androidx.compose.ui.graphics.Color.White,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Level.SAFE, Level.SUSPICIOUS, Level.CRITICAL, Level.INDETERMINATE).forEach {
                AssistChip(onClick = {}, label = { Text(StatusColors.label(it)) })
            }
        }
        Text(
            "Each cell is one 4-second window; the number is synthetic probability as a percentage.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun DemoProtocolCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("How to run the real-vs-cloned comparison", fontWeight = FontWeight.Bold)
            Text("1 · Enrol the person from the home screen.", style = MaterialTheme.typography.bodyMedium)
            Text("2 · Run a genuine recording of them here, with their name selected.",
                style = MaterialTheme.typography.bodyMedium)
            Text("3 · Run a cloned version of the same voice. The previous result stays on screen " +
                "so the two sit side by side.", style = MaterialTheme.typography.bodyMedium)
            Text(
                "What you are looking for is not two different similarity scores — a good clone " +
                    "should score high on similarity. It is the second number moving while the " +
                    "first one stays put.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
