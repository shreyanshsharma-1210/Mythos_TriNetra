package com.trustmesh.app.vcd.ui.call

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trustmesh.app.vcd.VcdApp
import com.trustmesh.app.vcd.data.PhoneContact
import com.trustmesh.app.vcd.data.PhoneContacts
import com.trustmesh.app.vcd.data.db.CallHistoryEntity
import com.trustmesh.app.vcd.ui.components.MinTouchTarget
import com.trustmesh.app.vcd.ui.theme.CallColors
import com.trustmesh.app.vcd.ui.theme.StatusColors
import com.trustmesh.app.vcd.voip.CallManager
import com.trustmesh.app.vcd.voip.PeerDiscovery
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DialerTab(val label: String) { RECENTS("Recents"), CONTACTS("Contacts"), DEVICES("Devices") }

/**
 * The dialler.
 *
 * Shows the phone's own address book and a call history, because that is what a dialler is. What it
 * does not do is imply that tapping a contact reaches that person's phone number: every call this
 * app places goes to a TRINETRA device on the same Wi-Fi, and the header says so rather than
 * leaving the user to work it out when somebody unexpected answers. The contact you pick is a label
 * on the call, not its destination.
 */
@Composable
fun DialerScreen(
    app: VcdApp,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val available by CallManager.available.collectAsStateWithLifecycle()
    val peers by CallManager.peers.collectAsStateWithLifecycle()
    val searching by CallManager.searching.collectAsStateWithLifecycle()
    val enrolled by app.contacts.observeContacts().collectAsStateWithLifecycle(emptyList())
    val recents by app.callHistory.observeRecent().collectAsStateWithLifecycle(emptyList())

    var tab by remember { mutableStateOf(DialerTab.RECENTS) }
    var query by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var selectedContactId by remember { mutableStateOf<Long?>(null) }

    var phoneContacts by remember { mutableStateOf<List<PhoneContact>>(emptyList()) }
    var contactsGranted by remember { mutableStateOf(PhoneContacts.hasPermission(context)) }
    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> contactsGranted = granted }

    LaunchedEffect(contactsGranted) {
        if (contactsGranted) phoneContacts = PhoneContacts.load(context)
    }

    var pendingLabel by remember { mutableStateOf<String?>(null) }
    var choosingDevice by remember { mutableStateOf(false) }

    fun dial(label: String?) {
        when {
            peers.isEmpty() -> Unit
            peers.size == 1 -> CallManager.placeCall(app, peers.first(), selectedContactId, label)
            else -> {
                pendingLabel = label
                choosingDevice = true
            }
        }
    }

    if (choosingDevice) {
        DevicePicker(
            peers = peers,
            onDismiss = { choosingDevice = false },
            onPick = { peer ->
                choosingDevice = false
                CallManager.placeCall(app, peer, selectedContactId, pendingLabel)
            },
        )
    }

    if (showSettings) {
        SettingsSheet(
            app = app,
            available = available,
            enrolled = enrolled.filter { it.usableWithCurrentModel }.map { it.id to it.name },
            selectedContactId = selectedContactId,
            onSelectContact = { id ->
                selectedContactId = id
                if (available) CallManager.goAvailable(app, id)
            },
            onDismiss = { showSettings = false },
        )
    }

    Column(modifier.fillMaxSize()) {
            Header(
                available = available,
                peerCount = peers.size,
                contactName = enrolled.firstOrNull { it.id == selectedContactId }?.name,
                query = query,
                onQueryChange = { query = it },
                onToggleAvailable = { on ->
                    if (on) CallManager.goAvailable(app, selectedContactId) else CallManager.goUnavailable()
                },
                onSettings = { showSettings = true },
            )

            if (!micGranted) {
                MicNeeded(onRequestMic)
                return@Column
            }

            TabRow(
                selectedTabIndex = tab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = CallColors.brand,
            ) {
                DialerTab.entries.forEach { t ->
                    Tab(
                        selected = tab == t,
                        onClick = { tab = t },
                        text = { Text(t.label) },
                        modifier = Modifier.heightIn(min = MinTouchTarget),
                    )
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                if (peers.isEmpty()) {
                    item { NoDevicesNote(available) }
                }

                when (tab) {
                    DialerTab.RECENTS -> {
                        val list = recents.filter {
                            query.isBlank() ||
                                (it.contactLabel ?: it.peerName).contains(query, ignoreCase = true)
                        }
                        if (list.isEmpty()) {
                            item { EmptyNote("No calls yet.") }
                        } else {
                            items(list, key = { it.id }) { entry ->
                                RecentRow(entry) { dial(entry.contactLabel ?: entry.peerName) }
                                HorizontalDivider(Modifier.padding(start = 74.dp))
                            }
                        }
                    }

                    DialerTab.CONTACTS -> {
                        if (!contactsGranted) {
                            item {
                                ContactsPermissionCard {
                                    contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
                                }
                            }
                        } else {
                            val list = phoneContacts.filter {
                                query.isBlank() || it.name.contains(query, ignoreCase = true) ||
                                    (it.number?.contains(query) == true)
                            }
                            if (list.isEmpty()) {
                                item { EmptyNote("No matching contacts.") }
                            } else {
                                items(list, key = { it.id }) { contact ->
                                    ContactRow(contact, peers.isNotEmpty()) { dial(contact.name) }
                                    HorizontalDivider(Modifier.padding(start = 74.dp))
                                }
                            }
                        }
                    }

                    DialerTab.DEVICES -> {
                        item { DevicesHeader(searching, available) { CallManager.rescan() } }
                        items(peers, key = { it.name }) { peer ->
                            DeviceRow(peer) { CallManager.placeCall(app, peer, selectedContactId, null) }
                            HorizontalDivider(Modifier.padding(start = 74.dp))
                        }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
}

@Composable
private fun Header(
    available: Boolean,
    peerCount: Int,
    contactName: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleAvailable: (Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(CallColors.brand)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("TRINETRA", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text(
                    when {
                        !available -> "Hidden · you cannot be called"
                        peerCount == 0 -> "Reachable · looking for devices"
                        peerCount == 1 -> "Reachable · 1 device nearby"
                        else -> "Reachable · $peerCount devices nearby"
                    },
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = available,
                onCheckedChange = onToggleAvailable,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color.White.copy(alpha = 0.45f),
                    uncheckedThumbColor = Color.White.copy(alpha = 0.8f),
                    uncheckedTrackColor = Color.White.copy(alpha = 0.2f),
                ),
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search name or number", color = Color.White.copy(alpha = 0.7f)) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = Color.White.copy(alpha = 0.9f)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White.copy(alpha = 0.16f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.16f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color.White,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSettings),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                null,
                tint = if (contactName != null) Color.White else Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp),
            )
            Text(
                contactName?.let { "Callers checked against $it" }
                    ?: "No voice selected — tap to choose",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Text("Change", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SettingsSheet(
    app: VcdApp,
    available: Boolean,
    enrolled: List<Pair<Long, String>>,
    selectedContactId: Long?,
    onSelectContact: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(CallManager.displayName(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Call settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        CallManager.setDisplayName(context, it)
                    },
                    label = { Text("Your name on this network") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Check callers against", fontWeight = FontWeight.Bold)
                if (enrolled.isEmpty()) {
                    Text(
                        "No voices are enrolled, so only the synthetic-speech half of the " +
                            "pipeline can run. Enrol someone to get an identity check too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.suspicious,
                    )
                } else {
                    TextButton(onClick = { onSelectContact(null) }) {
                        Text(if (selectedContactId == null) "• Nobody" else "Nobody")
                    }
                    enrolled.forEach { (id, label) ->
                        TextButton(onClick = { onSelectContact(id) }) {
                            Text(if (selectedContactId == id) "• $label" else label)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun DevicePicker(
    peers: List<PeerDiscovery.Peer>,
    onDismiss: () -> Unit,
    onPick: (PeerDiscovery.Peer) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which device?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Calls go to a device on this Wi-Fi, not to a phone number.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                peers.forEach { peer ->
                    TextButton(onClick = { onPick(peer) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${peer.name}  ·  ${peer.host}")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MicNeeded(onRequest: () -> Unit) {
    Card(Modifier.padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Microphone access is needed", fontWeight = FontWeight.Bold)
            Text(
                "A call needs the microphone to send your voice to the other person.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onRequest) { Text("Grant microphone access") }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        Modifier.padding(24.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
    )
}

@Composable
private fun NoDevicesNote(available: Boolean) {
    Card(
        Modifier.padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (available) "No devices found yet" else "You are not reachable",
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (available) {
                    "Calls need another phone running this app with the switch above turned on, " +
                        "on the same Wi-Fi. Guest networks and hotspots often stop phones from " +
                        "seeing each other."
                } else {
                    "Turn on the switch above to find other devices and to be called."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ContactsPermissionCard(onRequest: () -> Unit) {
    Card(Modifier.padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Show your contacts", fontWeight = FontWeight.Bold)
            Text(
                "Reads your address book so you can dial by name. The names only fill this list — " +
                    "nothing is stored or sent anywhere. Calls still go to a TRINETRA device on " +
                    "this Wi-Fi, not to a phone number.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onRequest) { Text("Allow contacts") }
        }
    }
}

@Composable
private fun DevicesHeader(searching: Boolean, available: Boolean, onRescan: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "ON THIS NETWORK",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.weight(1f),
        )
        if (searching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        TextButton(onClick = onRescan, enabled = available) { Text("Rescan") }
    }
}

@Composable
private fun RecentRow(entry: CallHistoryEntity, onDial: () -> Unit) {
    val format = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
    val title = entry.contactLabel ?: entry.peerName
    val missed = entry.ending == "MISSED" || entry.ending == "DECLINED" || entry.ending == "UNANSWERED"

    ListRow(
        title = title,
        titleColour = if (missed) StatusColors.critical else MaterialTheme.colorScheme.onBackground,
        subtitle = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    if (entry.outgoing) Icons.Filled.CallMade else Icons.Filled.CallReceived,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = if (missed) StatusColors.critical
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                )
                Text(
                    buildString {
                        append(format.format(Date(entry.startedAtEpochMs)))
                        if (entry.durationSeconds > 0) {
                            append(" · %d:%02d".format(entry.durationSeconds / 60, entry.durationSeconds % 60))
                        } else if (missed) {
                            append(" · ${entry.ending.lowercase().replaceFirstChar { it.uppercase() }}")
                        }
                        // The device that actually answered, when it differs from the label.
                        if (entry.contactLabel != null && entry.contactLabel != entry.peerName) {
                            append(" · ${entry.peerName}")
                        }
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        },
        enabled = true,
        onClick = onDial,
    )
}

@Composable
private fun ContactRow(contact: PhoneContact, canDial: Boolean, onDial: () -> Unit) {
    ListRow(
        title = contact.name,
        titleColour = MaterialTheme.colorScheme.onBackground,
        subtitle = {
            Text(
                contact.number ?: "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        },
        enabled = canDial,
        onClick = onDial,
    )
}

@Composable
private fun DeviceRow(peer: PeerDiscovery.Peer, onCall: () -> Unit) {
    ListRow(
        title = peer.name,
        titleColour = MaterialTheme.colorScheme.onBackground,
        subtitle = {
            Text(
                peer.host,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        },
        enabled = true,
        onClick = onCall,
    )
}

@Composable
private fun ListRow(
    title: String,
    titleColour: Color,
    subtitle: @Composable () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Avatar(title, size = 44)
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = titleColour, fontSize = 16.sp)
            subtitle()
        }
        Box(
            Modifier
                .size(36.dp)
                .background(
                    if (enabled) StatusColors.safe.copy(alpha = 0.12f) else Color.Transparent,
                    RoundedCornerShape(18.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Call,
                contentDescription = "Call $title",
                tint = if (enabled) StatusColors.safe
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
