package com.trustmesh.app.ui.screens.settings

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.trustmesh.app.core.firewall.OverlayPermissionHelper
import com.trustmesh.app.core.protection.ProtectionAction
import com.trustmesh.app.core.protection.ProtectionMode
import com.trustmesh.app.core.settings.TrustMeshSettings
import com.trustmesh.app.data.local.TrustMeshDatabase
import com.trustmesh.app.data.local.entity.ProtectionPolicyEntity
import com.trustmesh.app.data.local.entity.TrustedCallerEntity
import com.trustmesh.app.data.repository.ProtectionPolicyRepository
import com.trustmesh.app.firewall.NotificationAccessHelper
import com.trustmesh.app.firewall.RoleManagerHelper
import com.trustmesh.app.ui.theme.*
import kotlinx.coroutines.launch

private val DarkSurface = Color(0xFF15181D)
private val DarkCard = Color(0xFF1E2229)
private val AccentBlue = Color(0xFF4285F4)
private val AccentGreen = Color(0xFF34A853)
private val AccentYellow = Color(0xFFFBBC05)
private val AccentRed = Color(0xFFEA4335)
private val TextPrimaryW = Color.White
private val TextSecondaryW = Color(0xFFAAAAAA)

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var isCallScreeningActive by remember { mutableStateOf(RoleManagerHelper.isCallScreeningRoleGranted(context)) }
    var isNotificationAccessGranted by remember { mutableStateOf(NotificationAccessHelper.isNotificationAccessGranted(context)) }
    var isOverlayPermissionGranted by remember { mutableStateOf(OverlayPermissionHelper.hasOverlayPermission(context)) }

    val db = remember { TrustMeshDatabase.getDatabase(context) }
    val policyRepository = remember { ProtectionPolicyRepository(db.protectionPolicyDao(), db.trustedCallerDao()) }

    val policy by policyRepository.getPolicyFlow().collectAsState(initial = ProtectionPolicyEntity.default())
    val trustedCallers by policyRepository.getTrustedCallersFlow().collectAsState(initial = emptyList())

    var showCustomizer by remember { mutableStateOf(false) }
    var showTrustedCallersDialog by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isCallScreeningActive = RoleManagerHelper.isCallScreeningRoleGranted(context)
                isNotificationAccessGranted = NotificationAccessHelper.isNotificationAccessGranted(context)
                isOverlayPermissionGranted = OverlayPermissionHelper.hasOverlayPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isCallScreeningActive = RoleManagerHelper.isCallScreeningRoleGranted(context)
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isNotificationAccessGranted = NotificationAccessHelper.isNotificationAccessGranted(context)
    }

    if (showTrustedCallersDialog) {
        TrustedCallersDialog(
            trustedCallers = trustedCallers,
            onAddCaller = { number, name ->
                scope.launch { policyRepository.addTrustedCaller(number, name) }
            },
            onRemoveCaller = { number ->
                scope.launch { policyRepository.removeTrustedCaller(number) }
            },
            onDismiss = { showTrustedCallersDialog = false }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(TrustMeshBackground)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {

        // ─── HEADER ────────────────────────────────────────────────────────
        item {
            Spacer(Modifier.height(16.dp))
            Text("Settings", style = Typography.headlineMedium, color = TextPrimary,
                fontWeight = FontWeight.Bold, fontSize = 28.sp)
            Spacer(Modifier.height(24.dp))
        }

        // ─── PROTECTION MODE ────────────────────────────────────────────────
        item {
            SectionHeader("🛡  Protection Mode")
            Spacer(Modifier.height(12.dp))

            ProtectionModeSelector(
                currentMode = policy.mode,
                onModeSelected = { selectedMode ->
                    scope.launch { policyRepository.setMode(selectedMode) }
                    showCustomizer = selectedMode == ProtectionMode.CUSTOM
                }
            )

            AnimatedVisibility(
                visible = policy.mode == ProtectionMode.CUSTOM,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                ProtectionCustomizer(
                    policy = policy,
                    onUpdate = { low, elevated, high, critical, unknown, autoBlock ->
                        scope.launch {
                            policyRepository.updateCustomPolicy(low, elevated, high, critical, unknown, autoBlock)
                        }
                    }
                )
            }
            Spacer(Modifier.height(20.dp))
        }

        // ─── TRUSTED CALLERS ────────────────────────────────────────────────
        item {
            SectionHeader("✅  Trusted Callers")
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Callers in this list skip TrustMesh risk checks. Only add people you fully trust.",
                        color = TextSecondaryW, fontSize = 13.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    if (trustedCallers.isEmpty()) {
                        Text("No trusted callers added yet.", color = TextSecondaryW, fontSize = 13.sp)
                    } else {
                        trustedCallers.take(3).forEach { caller ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(caller.name, color = TextPrimaryW, fontWeight = FontWeight.Medium)
                                    Text(caller.phoneNumber, color = TextSecondaryW, fontSize = 12.sp)
                                }
                                TextButton(onClick = {
                                    scope.launch { policyRepository.removeTrustedCaller(caller.phoneNumber) }
                                }) {
                                    Text("Remove", color = AccentRed, fontSize = 12.sp)
                                }
                            }
                        }
                        if (trustedCallers.size > 3) {
                            Text("+${trustedCallers.size - 3} more", color = TextSecondaryW, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { showTrustedCallersDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) {
                        Text("Manage Trusted Callers")
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        // ─── PERMISSIONS ────────────────────────────────────────────────────
        item {
            SectionHeader("🔐  Permissions")
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                Column(Modifier.padding(12.dp)) {
                    PermissionRow(
                        label = "Call Screening",
                        description = "Observe incoming call metadata",
                        granted = isCallScreeningActive,
                        onClick = {
                            if (!isCallScreeningActive) {
                                val intent = RoleManagerHelper.getCallScreeningRoleIntent(context)
                                if (intent != null) roleLauncher.launch(intent)
                            }
                        }
                    )
                    HorizontalDivider(color = Color(0xFF2A2F38), modifier = Modifier.padding(vertical = 4.dp))
                    PermissionRow(
                        label = "Notification Monitoring",
                        description = "Observe notification signals",
                        granted = isNotificationAccessGranted,
                        onClick = {
                            if (!isNotificationAccessGranted) {
                                notificationLauncher.launch(NotificationAccessHelper.getNotificationAccessIntent())
                            }
                        }
                    )
                    HorizontalDivider(color = Color(0xFF2A2F38), modifier = Modifier.padding(vertical = 4.dp))
                    PermissionRow(
                        label = "Overlay Permission",
                        description = "Show real-time protection UI",
                        granted = isOverlayPermissionGranted,
                        onClick = {
                            if (!isOverlayPermissionGranted) {
                                OverlayPermissionHelper.requestOverlayPermission(context)
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        // ─── PRIVACY & IDENTITY ─────────────────────────────────────────────
        item {
            SectionHeader("🔎  Privacy & Identity")
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                Column(Modifier.padding(16.dp)) {
                    val isExternalLookupEnabled by TrustMeshSettings.isExternalLookupEnabled.collectAsState()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("External Caller Lookup", color = TextPrimaryW, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Query external caller directory for reputations",
                                color = TextSecondaryW, fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = isExternalLookupEnabled,
                            onCheckedChange = { TrustMeshSettings.setExternalLookupEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AccentBlue,
                                checkedTrackColor = AccentBlue.copy(alpha = 0.4f)
                            )
                        )
                    }
                    if (isExternalLookupEnabled) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "⚠ Only the incoming phone number is queried. Your contacts are never uploaded.",
                            color = AccentYellow, fontSize = 12.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        // ─── PRIVACY GUARANTEE ──────────────────────────────────────────────
        item {
            SectionHeader("🔒  Privacy Guarantee")
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrivacyGuaranteeRow("✓", "Zero audio recording or processing")
                    PrivacyGuaranteeRow("✓", "No call content accessed")
                    PrivacyGuaranteeRow("✓", "Local-first data storage")
                    PrivacyGuaranteeRow("✓", "No contact data uploaded")
                    PrivacyGuaranteeRow("✓", "No Accessibility Service used")
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─── PROTECTION MODE SELECTOR ──────────────────────────────────────────────────

@Composable
fun ProtectionModeSelector(currentMode: ProtectionMode, onModeSelected: (ProtectionMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProtectionModeCard(
            mode = ProtectionMode.STANDARD,
            title = "Standard",
            description = "Monitor + warn on high/critical risks. Recommended for most users.",
            selected = currentMode == ProtectionMode.STANDARD,
            accentColor = AccentBlue,
            onSelect = { onModeSelected(ProtectionMode.STANDARD) }
        )
        ProtectionModeCard(
            mode = ProtectionMode.STRICT,
            title = "Strict",
            description = "Elevated warnings at all risk levels. Auto-block critical callers.",
            selected = currentMode == ProtectionMode.STRICT,
            accentColor = AccentYellow,
            onSelect = { onModeSelected(ProtectionMode.STRICT) }
        )
        ProtectionModeCard(
            mode = ProtectionMode.CUSTOM,
            title = "Custom",
            description = "Configure each risk level's behavior independently.",
            selected = currentMode == ProtectionMode.CUSTOM,
            accentColor = AccentGreen,
            onSelect = { onModeSelected(ProtectionMode.CUSTOM) }
        )
    }
}

@Composable
fun ProtectionModeCard(
    mode: ProtectionMode,
    title: String,
    description: String,
    selected: Boolean,
    accentColor: Color,
    onSelect: () -> Unit
) {
    val borderColor = if (selected) accentColor else Color(0xFF2A2F38)
    val bgColor = if (selected) accentColor.copy(alpha = 0.08f) else DarkCard

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onSelect() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = accentColor)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = if (selected) accentColor else TextPrimaryW, fontWeight = FontWeight.SemiBold)
            Text(description, color = TextSecondaryW, fontSize = 12.sp)
        }
    }
}

// ─── CUSTOM POLICY EDITOR ──────────────────────────────────────────────────────

@Composable
fun ProtectionCustomizer(
    policy: ProtectionPolicyEntity,
    onUpdate: (ProtectionAction, ProtectionAction, ProtectionAction, ProtectionAction, ProtectionAction, Boolean) -> Unit
) {
    var lowRisk by remember(policy) { mutableStateOf(policy.lowRiskBehavior) }
    var elevatedRisk by remember(policy) { mutableStateOf(policy.elevatedRiskBehavior) }
    var highRisk by remember(policy) { mutableStateOf(policy.highRiskBehavior) }
    var criticalRisk by remember(policy) { mutableStateOf(policy.criticalRiskBehavior) }
    var unknownCaller by remember(policy) { mutableStateOf(policy.unknownCallerBehavior) }
    var autoBlock by remember(policy) { mutableStateOf(policy.autoBlockCritical) }

    SettingsCard(modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Custom Behavior", color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))

            PolicyActionRow("Low Risk", lowRisk, Color(0xFF34A853)) { lowRisk = it; onUpdate(lowRisk, elevatedRisk, highRisk, criticalRisk, unknownCaller, autoBlock) }
            PolicyActionRow("Elevated Risk", elevatedRisk, AccentYellow) { elevatedRisk = it; onUpdate(lowRisk, elevatedRisk, highRisk, criticalRisk, unknownCaller, autoBlock) }
            PolicyActionRow("High Risk", highRisk, Color(0xFFFF6D00)) { highRisk = it; onUpdate(lowRisk, elevatedRisk, highRisk, criticalRisk, unknownCaller, autoBlock) }
            PolicyActionRow("Critical Risk", criticalRisk, AccentRed) { criticalRisk = it; onUpdate(lowRisk, elevatedRisk, highRisk, criticalRisk, unknownCaller, autoBlock) }
            PolicyActionRow("Unknown Caller", unknownCaller, Color(0xFF9E9E9E)) { unknownCaller = it; onUpdate(lowRisk, elevatedRisk, highRisk, criticalRisk, unknownCaller, autoBlock) }

            HorizontalDivider(color = Color(0xFF2A2F38), modifier = Modifier.padding(vertical = 12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-block Critical", color = TextPrimaryW, fontWeight = FontWeight.SemiBold)
                    Text("Requires explicit user opt-in", color = AccentRed, fontSize = 11.sp)
                }
                Switch(
                    checked = autoBlock,
                    onCheckedChange = {
                        autoBlock = it
                        onUpdate(lowRisk, elevatedRisk, highRisk, criticalRisk, unknownCaller, autoBlock)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AccentRed,
                        checkedTrackColor = AccentRed.copy(alpha = 0.4f)
                    )
                )
            }
        }
    }
}

@Composable
fun PolicyActionRow(label: String, currentAction: ProtectionAction, dotColor: Color, onActionChanged: (ProtectionAction) -> Unit) {
    val displayableActions = listOf(
        ProtectionAction.MONITOR_ONLY to "Monitor Only",
        ProtectionAction.SHOW_COMPACT_WARNING to "Compact Warning",
        ProtectionAction.SHOW_RISK_CARD to "Risk Card",
        ProtectionAction.SHOW_BOTTOM_SHEET to "Bottom Sheet",
        ProtectionAction.SHOW_SECURITY_INTERVENTION to "Full Intervention",
        ProtectionAction.ASK_USER to "Ask User"
    )

    var expanded by remember { mutableStateOf(false) }
    val currentLabel = displayableActions.find { it.first == currentAction }?.second ?: currentAction.name

    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(dotColor, shape = RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(8.dp))
            Text(label, color = TextPrimaryW, fontSize = 14.sp)
        }
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(currentLabel, color = AccentBlue, fontSize = 13.sp)
                Text(" ▾", color = AccentBlue, fontSize = 11.sp)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(DarkCard)
            ) {
                displayableActions.forEach { (action, name) ->
                    DropdownMenuItem(
                        text = { Text(name, color = if (action == currentAction) AccentBlue else TextPrimaryW) },
                        onClick = { onActionChanged(action); expanded = false }
                    )
                }
            }
        }
    }
}

// ─── TRUSTED CALLERS DIALOG ────────────────────────────────────────────────────

@Composable
fun TrustedCallersDialog(
    trustedCallers: List<TrustedCallerEntity>,
    onAddCaller: (String, String) -> Unit,
    onRemoveCaller: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newNumber by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkCard,
        title = { Text("Trusted Callers", color = TextPrimaryW, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "These callers bypass TrustMesh risk analysis.",
                    color = TextSecondaryW, fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))

                if (trustedCallers.isEmpty()) {
                    Text("No trusted callers.", color = TextSecondaryW, fontSize = 13.sp)
                } else {
                    trustedCallers.forEach { caller ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(caller.name, color = TextPrimaryW, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(caller.phoneNumber, color = TextSecondaryW, fontSize = 12.sp)
                            }
                            TextButton(onClick = { onRemoveCaller(caller.phoneNumber) }) {
                                Text("✕", color = AccentRed)
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF2A2F38), modifier = Modifier.padding(vertical = 8.dp))

                Text("Add Trusted Caller", color = AccentGreen, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name", color = TextSecondaryW, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = Color(0xFF2A2F38),
                        focusedTextColor = TextPrimaryW,
                        unfocusedTextColor = TextPrimaryW
                    )
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newNumber,
                    onValueChange = { newNumber = it },
                    label = { Text("Phone number", color = TextSecondaryW, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = Color(0xFF2A2F38),
                        focusedTextColor = TextPrimaryW,
                        unfocusedTextColor = TextPrimaryW
                    )
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (newNumber.isNotBlank() && newName.isNotBlank()) {
                            onAddCaller(newNumber.trim(), newName.trim())
                            newNumber = ""
                            newName = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    enabled = newNumber.isNotBlank() && newName.isNotBlank()
                ) {
                    Text("Add Caller")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = AccentBlue) }
        }
    )
}

// ─── SHARED COMPONENTS ─────────────────────────────────────────────────────────

@Composable
fun SectionHeader(text: String) {
    Text(text, color = TextPrimaryW, fontWeight = FontWeight.Bold, fontSize = 16.sp)
}

@Composable
fun SettingsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = DarkCard,
        shadowElevation = 4.dp
    ) {
        content()
    }
}

@Composable
fun PermissionRow(label: String, description: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !granted) { onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = TextPrimaryW, fontWeight = FontWeight.Medium)
            Text(description, color = TextSecondaryW, fontSize = 12.sp)
        }
        if (granted) {
            Text("✓  Granted", color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        } else {
            Text("Enable →", color = AccentYellow, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

@Composable
fun PrivacyGuaranteeRow(icon: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = TextSecondaryW, fontSize = 13.sp)
    }
}
