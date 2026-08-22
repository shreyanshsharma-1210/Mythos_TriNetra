package com.trustmesh.app.vcd.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trustmesh.app.vcd.VcdApp
import com.trustmesh.app.vcd.data.EnrolledContact
import com.trustmesh.app.vcd.pipeline.Fusion
import com.trustmesh.app.vcd.ui.components.InfoCard
import com.trustmesh.app.vcd.ui.components.MinTouchTarget
import com.trustmesh.app.vcd.ui.components.SectionTitle
import com.trustmesh.app.vcd.ui.components.VcdHeader
import com.trustmesh.app.vcd.ui.components.VcdIconButton
import com.trustmesh.app.vcd.ui.components.VcdListRow
import com.trustmesh.app.vcd.ui.permission.hasMicPermission
import com.trustmesh.app.vcd.ui.theme.StatusColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The people this app can recognise.
 *
 * One row per enrolled voice, saying plainly what the app can actually do for that person — because
 * that varies, and the variation matters. A contact whose anti-spoofing baseline came back unusable
 * gets identity matching only, and finding that out mid-call is too late.
 */
@Composable
fun VoicesScreen(
    app: VcdApp,
    modifier: Modifier = Modifier,
    onEnroll: () -> Unit,
    onLive: (Long?) -> Unit,
    onPermission: () -> Unit,
) {
    val context = LocalContext.current
    val contacts by app.contacts.observeContacts().collectAsStateWithLifecycle(emptyList())
    val modelStatus by app.models.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var pendingDelete by remember { mutableStateOf<EnrolledContact?>(null) }
    val micGranted = remember { hasMicPermission(context) }

    pendingDelete?.let { contact ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${contact.name}'s voiceprint?") },
            text = {
                Text(
                    "The stored voiceprint is erased and cannot be recovered. ${contact.name} " +
                        "would have to record again to be recognised."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { app.contacts.delete(contact.id) }
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = StatusColors.critical),
                ) { Text("Delete permanently") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    Column(modifier.fillMaxSize()) {
        VcdHeader(
            title = "Voices",
            subtitle = when (contacts.size) {
                0 -> "Nobody enrolled yet"
                1 -> "1 voice enrolled"
                else -> "${contacts.size} voices enrolled"
            },
            trailing = {
                VcdIconButton(
                    icon = Icons.Filled.Add,
                    description = "Enrol a new voice",
                    onClick = onEnroll,
                )
            },
        )

        LazyColumn(Modifier.fillMaxSize()) {
            if (modelStatus is com.trustmesh.app.vcd.ml.ModelRuntime.Status.Unavailable) {
                item {
                    InfoCard("Voice models are not available", accent = StatusColors.critical) {
                        Text(
                            (modelStatus as com.trustmesh.app.vcd.ml.ModelRuntime.Status.Unavailable).message,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (!micGranted) {
                item {
                    InfoCard("Microphone access is needed", accent = StatusColors.suspicious) {
                        Text(
                            "Enrolling a voice records about a minute of speech, so the app needs " +
                                "the microphone.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onPermission) { Text("Review microphone access") }
                    }
                }
            }

            if (contacts.isEmpty()) {
                item { EmptyVoices(onEnroll) }
            } else {
                item { SectionTitle("ENROLLED") }
                items(contacts, key = { it.id }) { contact ->
                    VoiceRow(
                        contact = contact,
                        onVerify = { onLive(contact.id) },
                        onDelete = { pendingDelete = contact },
                    )
                    HorizontalDivider(Modifier.padding(start = 74.dp))
                }
            }

            item {
                InfoCard("What is stored") {
                    Text(
                        "Only a mathematical summary of each voice — a 256-number vector — " +
                            "encrypted on this device. The recording itself is erased as soon as " +
                            "the summary is made, and never leaves the phone.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun VoiceRow(contact: EnrolledContact, onVerify: () -> Unit, onDelete: () -> Unit) {
    val format = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    val spoofCheck = Fusion.spoofCheckStatus(contact.baselineSynthetic)

    VcdListRow(
        avatarSeed = contact.name,
        title = contact.name,
        onClick = if (contact.usableWithCurrentModel) onVerify else null,
        enabled = contact.usableWithCurrentModel,
        trailingIcon = Icons.Filled.Delete,
        trailingTint = StatusColors.critical,
        trailingDescription = "Delete ${contact.name}'s voiceprint",
        onTrailingClick = onDelete,
        subtitle = {
            Column {
                Text(
                    buildString {
                        contact.relationship?.let { append(it).append(" · ") }
                        append("${contact.enrolledSeconds.toInt()} s")
                        append(" · ")
                        append(format.format(Date(contact.createdAtEpochMs)))
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
                // What the app can actually do for this person, stated here rather than discovered
                // during a call.
                val (note, colour) = when {
                    !contact.usableWithCurrentModel ->
                        "Enrolled with an older model — re-enrol to use" to StatusColors.critical

                    spoofCheck == Fusion.SpoofCheck.UNRELIABLE ->
                        "Voice match only — clone check does not work on this voice" to StatusColors.suspicious

                    spoofCheck == Fusion.SpoofCheck.NO_BASELINE ->
                        "Clone check uncalibrated — re-enrol to calibrate" to StatusColors.suspicious

                    else -> "Voice match and clone check both active" to StatusColors.safe
                }
                Text(note, fontSize = 12.sp, color = colour, fontWeight = FontWeight.SemiBold)
            }
        },
    )
    if (contact.usableWithCurrentModel) {
        Row(
            Modifier.padding(start = 74.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val app = LocalContext.current.applicationContext as? com.trustmesh.app.vcd.VcdApp
            val peers by com.trustmesh.app.vcd.voip.CallManager.peers.collectAsStateWithLifecycle()

            TextButton(
                onClick = {
                    if (app != null) {
                        val peer = peers.firstOrNull()
                        if (peer != null) {
                            com.trustmesh.app.vcd.voip.CallManager.placeCall(app, peer, contact.id, contact.name)
                        } else {
                            android.widget.Toast.makeText(
                                app,
                                "Searching for local TriNetra devices over Wi-Fi...",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            com.trustmesh.app.vcd.voip.CallManager.rescan()
                        }
                    }
                },
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) {
                Icon(Icons.Filled.Phone, contentDescription = "WebRTC VoIP Call", modifier = Modifier.height(18.dp))
                Text("  📞 WebRTC Call")
            }

            TextButton(
                onClick = onVerify,
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.height(18.dp))
                Text("  Screen")
            }
        }
    }
}

@Composable
private fun EmptyVoices(onEnroll: () -> Unit) {
    InfoCard("No voices enrolled yet") {
        Text(
            "Enrol someone you might get an urgent call from. They record about a minute of " +
                "speech, and the app keeps only a mathematical summary of their voice — never " +
                "the recording.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = onEnroll,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("  Enrol a voice")
        }
    }
}
