package com.trustmesh.app.ui.screens.protection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trustmesh.app.MainActivity
import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.core.identity.CallerIdentity
import com.trustmesh.app.core.identity.IdentitySource
import com.trustmesh.app.interaction.InteractionManager

enum class OverlayPresentationMode {
    COMPACT, FLOATING, BOTTOM_SHEET, FULL_SCREEN
}

@Composable
fun ProtectionOverlay(
    state: CallOverlayState,
    callerIdentity: CallerIdentity?,
    callerReputation: com.trustmesh.app.core.identity.CallerReputation?,
    fallbackName: String,
    fallbackNumber: String,
    riskLevel: RiskLevel,
    riskAssessment: com.trustmesh.app.core.intelligence.risk.RiskAssessment?,
    activeIncident: com.trustmesh.app.core.incident.SecurityIncident? = null,
    activeCallDurationSeconds: Long = 0,
    onDismiss: () -> Unit
) {
    val identityName = callerIdentity?.displayName ?: "Unknown Caller"
    android.util.Log.d("TrustMeshIdentity", "overlayRecomposed=true identity=$identityName")
    
    when (state) {
        CallOverlayState.INCOMING -> {
            if (riskLevel == RiskLevel.CRITICAL) {
                FullScreenSecurityOverlay(callerIdentity, callerReputation, fallbackName, fallbackNumber, riskLevel, riskAssessment, activeIncident, onDismiss)
            } else {
                // Incoming cards must be centered. We display FloatingRiskCard for low, elevated, and high risk levels.
                // We style them according to the risk level.
                FloatingRiskCard(callerIdentity, callerReputation, fallbackName, fallbackNumber, riskLevel, riskAssessment, activeIncident, onDismiss)
            }
        }
        CallOverlayState.ACTIVE -> {
            ActiveCallStatusBox(callerIdentity, fallbackNumber, riskLevel, riskAssessment, activeIncident, onDismiss)
        }
        CallOverlayState.SUMMARY -> {
            val interactions by InteractionManager.interactions.collectAsState()
            val interaction = interactions.firstOrNull()
            CallSummaryOverlay(interaction, activeIncident, activeCallDurationSeconds, onDismiss)
        }
        CallOverlayState.HIDDEN -> {
            // Drawn nothing when hidden
        }
    }
}

@Composable
fun ActiveCallStatusBox(
    callerIdentity: CallerIdentity?,
    fallbackNumber: String,
    riskLevel: RiskLevel,
    riskAssessment: com.trustmesh.app.core.intelligence.risk.RiskAssessment?,
    activeIncident: com.trustmesh.app.core.incident.SecurityIncident?,
    onDismiss: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .padding(8.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    ProtectionController.updatePosition(dragAmount.x.toInt(), dragAmount.y.toInt())
                }
            },
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF15181D).copy(alpha = 0.95f),
        border = BorderStroke(
            width = 1.5.dp,
            color = when (riskLevel) {
                RiskLevel.CRITICAL -> Color(0xFFEA4335)
                RiskLevel.HIGH -> Color(0xFFEA4335)
                RiskLevel.ELEVATED -> Color(0xFFFBBC05)
                RiskLevel.LOW -> Color(0xFF34A853)
            }
        ),
        shadowElevation = 8.dp
    ) {
        if (!isExpanded) {
            Row(
                modifier = Modifier
                    .clickable { isExpanded = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🛡 TrustMesh: ${riskLevel.displayName}",
                    color = when (riskLevel) {
                        RiskLevel.CRITICAL, RiskLevel.HIGH -> Color(0xFFEA4335)
                        RiskLevel.ELEVATED -> Color(0xFFFBBC05)
                        RiskLevel.LOW -> Color(0xFF34A853)
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                val identifier = callerIdentity?.displayName ?: callerIdentity?.phoneNumber ?: fallbackNumber
                if (identifier.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "• $identifier",
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .width(260.dp)
                    .clickable { isExpanded = false }
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🛡 TrustMesh Details", color = Color(0xFF4285F4), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        text = "Collapse",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                val name = callerIdentity?.displayName ?: "Unknown Caller"
                val num = callerIdentity?.phoneNumber ?: fallbackNumber
                Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (num.isNotBlank()) {
                    Text(num, color = Color.Gray, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Risk Level: ", color = Color.LightGray, fontSize = 13.sp)
                    Text(
                        text = riskLevel.displayName,
                        color = when (riskLevel) {
                            RiskLevel.CRITICAL, RiskLevel.HIGH -> Color(0xFFEA4335)
                            RiskLevel.ELEVATED -> Color(0xFFFBBC05)
                            RiskLevel.LOW -> Color(0xFF34A853)
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                val explanation = activeIncident?.explanation ?: riskAssessment?.attackContext?.explanation ?: riskAssessment?.explanation ?: "Monitoring call events locally..."
                Spacer(modifier = Modifier.height(6.dp))
                Text(explanation, color = Color.LightGray, fontSize = 12.sp)

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            onDismiss()
                        },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Dismiss Overlay", color = Color(0xFFEA4335), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun CallSummaryOverlay(
    interaction: com.trustmesh.app.interaction.Interaction?,
    activeIncident: com.trustmesh.app.core.incident.SecurityIncident?,
    activeCallDurationSeconds: Long,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier
            .padding(16.dp)
            .width(320.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF15181D),
        shadowElevation = 16.dp,
        border = BorderStroke(width = 1.5.dp, color = Color(0xFF4285F4))
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🛡", fontSize = 20.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("TrustMesh Call Summary", color = Color(0xFF4285F4), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))

            val callerIdentity = interaction?.callerIdentity
            val displayName = callerIdentity?.displayName ?: "Unknown Name"
            val phoneNumber = callerIdentity?.phoneNumber ?: interaction?.title ?: "Not available"

            Text("Caller:", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(displayName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(phoneNumber, color = Color.LightGray, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(10.dp))

            val dateTime = interaction?.timestamp ?: "Not available"
            Text("Date / Time:", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(dateTime, color = Color.White, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(10.dp))

            val durationText = if (activeCallDurationSeconds > 0) "$activeCallDurationSeconds seconds" else "Not available"
            Text("Call Duration:", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(durationText, color = Color.White, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(10.dp))

            val riskLevel = interaction?.riskLevel ?: RiskLevel.LOW
            val score = interaction?.riskAssessment?.score ?: 0
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Risk Level:", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = riskLevel.displayName,
                        color = when (riskLevel) {
                            RiskLevel.CRITICAL, RiskLevel.HIGH -> Color(0xFFEA4335)
                            RiskLevel.ELEVATED -> Color(0xFFFBBC05)
                            RiskLevel.LOW -> Color(0xFF34A853)
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Risk Score:", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text("$score / 100", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            val rep = interaction?.callerReputation
            if (rep != null && rep.reputationLevel != com.trustmesh.app.core.identity.ReputationLevel.UNKNOWN) {
                Text("Reputation:", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "${rep.reputationLevel.name.replace("_", " ")} (${rep.source})",
                    color = if (rep.reputationLevel == com.trustmesh.app.core.identity.ReputationLevel.HIGH_RISK) Color(0xFFEA4335) else Color(0xFFFBBC05),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (activeIncident != null) {
                Text("Security Incident:", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "${activeIncident.incidentType.name.replace("_", " ")} [${activeIncident.severity}]",
                    color = Color(0xFFEA4335),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            val explanation = interaction?.riskAssessment?.explanation ?: "No details available."
            Text("Explanation:", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(explanation, color = Color.LightGray, fontSize = 12.sp, maxLines = 3)
            Spacer(modifier = Modifier.height(8.dp))

            val factors = interaction?.riskAssessment?.factors ?: emptyList()
            if (factors.isNotEmpty()) {
                Text("Risk Factors:", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                factors.take(2).forEach { factor ->
                    Text("• ${factor.description}", color = Color.LightGray, fontSize = 11.sp)
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            val whatTrustMeshDid = if (riskLevel == RiskLevel.CRITICAL) {
                "Monitored call context and recommended full security intervention."
            } else {
                "Monitored call interaction locally and verified caller reputation."
            }
            Text("Action Taken:", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(whatTrustMeshDid, color = Color.LightGray, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "🛡 Zero call audio was recorded or processed locally or externally.",
                color = Color(0xFF34A853),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Close", color = Color.Gray)
                }

                Button(
                    onClick = {
                        val interactionId = interaction?.id ?: ""
                        if (interactionId.isNotBlank()) {
                            val intent = android.content.Intent(context, MainActivity::class.java).apply {
                                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra("navigate_to", "report/$interactionId")
                            }
                            context.startActivity(intent)
                        } else {
                            val intent = android.content.Intent(context, MainActivity::class.java).apply {
                                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra("navigate_to", "history")
                            }
                            context.startActivity(intent)
                        }
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text("View Report", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun CompactFloatingOverlay(
    callerIdentity: CallerIdentity?,
    callerReputation: com.trustmesh.app.core.identity.CallerReputation?,
    fallbackName: String,
    fallbackNumber: String,
    riskLevel: RiskLevel,
    riskAssessment: com.trustmesh.app.core.intelligence.risk.RiskAssessment?,
    activeIncident: com.trustmesh.app.core.incident.SecurityIncident?,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF15181D),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("🛡 TrustMesh", color = Color(0xFF4285F4), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(4.dp))
                if (callerIdentity?.isKnown == true) {
                    Text(callerIdentity.displayName ?: "Unknown", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Known contact", color = Color.Gray, fontSize = 12.sp)
                } else {
                    Text("Unknown Caller", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(callerIdentity?.phoneNumber ?: fallbackNumber, color = Color.Gray, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(riskLevel.displayName, color = Color(0xFF34A853), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (callerReputation != null && callerReputation.reputationLevel != com.trustmesh.app.core.identity.ReputationLevel.UNKNOWN) {
                    val repColor = if (callerReputation.reputationLevel == com.trustmesh.app.core.identity.ReputationLevel.HIGH_RISK) Color(0xFFEA4335) else Color(0xFFFBBC05)
                    Text("Reputation: ${callerReputation.reputationLevel.name.replace("_", " ")}", color = repColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                } else if (activeIncident != null) {
                    Text(activeIncident.incidentType.name.replace("_", " "), color = Color(0xFFEA4335), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text(riskAssessment?.attackContext?.inferredIntent?.name?.replace("_", " ") ?: "Monitoring interaction", color = Color.Gray, fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = Color.Gray)
            }
        }
    }
}

@Composable
fun FloatingRiskCard(
    callerIdentity: CallerIdentity?,
    callerReputation: com.trustmesh.app.core.identity.CallerReputation?,
    fallbackName: String,
    fallbackNumber: String,
    riskLevel: RiskLevel,
    riskAssessment: com.trustmesh.app.core.intelligence.risk.RiskAssessment?,
    activeIncident: com.trustmesh.app.core.incident.SecurityIncident?,
    onDismiss: () -> Unit
) {
    val themeColor = when (riskLevel) {
        RiskLevel.CRITICAL -> Color(0xFFEA4335)
        RiskLevel.HIGH -> Color(0xFFEA4335)
        RiskLevel.ELEVATED -> Color(0xFFFBBC05)
        RiskLevel.LOW -> Color(0xFF34A853)
    }

    Surface(
        modifier = Modifier
            .padding(16.dp)
            .width(300.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF15181D),
        border = BorderStroke(width = 1.5.dp, color = themeColor),
        shadowElevation = 12.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("🛡 TrustMesh", color = themeColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(12.dp))
            val title = if (callerIdentity?.isKnown == true) callerIdentity.displayName ?: "Unknown" else "Unknown Caller"
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (callerIdentity?.isKnown != true) {
                Text(callerIdentity?.phoneNumber ?: fallbackNumber, color = Color.LightGray, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Risk Level: ${riskLevel.displayName}", color = themeColor, fontWeight = FontWeight.Bold)

            if (callerReputation != null && callerReputation.reputationLevel != com.trustmesh.app.core.identity.ReputationLevel.UNKNOWN) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Directory: ${callerReputation.displayName ?: callerReputation.category.name}", color = Color.LightGray, fontSize = 14.sp)
                val repColor = if (callerReputation.reputationLevel == com.trustmesh.app.core.identity.ReputationLevel.HIGH_RISK) Color(0xFFEA4335) else Color(0xFFFBBC05)
                Text(callerReputation.reputationLevel.name.replace("_", " "), color = repColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))
            val contextText = activeIncident?.explanation ?: riskAssessment?.attackContext?.explanation ?: riskAssessment?.explanation ?: "Analyzing caller indicators locally..."
            Text(contextText, color = Color.LightGray, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Dismiss", color = Color.Gray) }
            }
        }
    }
}

@Composable
fun BottomRiskSheet(
    callerIdentity: CallerIdentity?,
    callerReputation: com.trustmesh.app.core.identity.CallerReputation?,
    fallbackName: String,
    fallbackNumber: String,
    riskLevel: RiskLevel,
    riskAssessment: com.trustmesh.app.core.intelligence.risk.RiskAssessment?,
    activeIncident: com.trustmesh.app.core.incident.SecurityIncident?,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = Color(0xFF15181D),
        shadowElevation = 16.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🛡 TrustMesh", color = Color(0xFFEA4335), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(riskLevel.displayName, color = Color(0xFFEA4335), fontWeight = FontWeight.Black, fontSize = 22.sp)
            Spacer(modifier = Modifier.height(16.dp))
            val title = if (callerIdentity?.isKnown == true) callerIdentity.displayName ?: "Unknown" else "Unknown Caller"
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (callerIdentity?.isKnown != true) {
                Text(callerIdentity?.phoneNumber ?: fallbackNumber, color = Color.LightGray, fontSize = 16.sp)
            }

            if (callerReputation != null && callerReputation.reputationLevel != com.trustmesh.app.core.identity.ReputationLevel.UNKNOWN) {
                Spacer(modifier = Modifier.height(8.dp))
                val repColor = if (callerReputation.reputationLevel == com.trustmesh.app.core.identity.ReputationLevel.HIGH_RISK) Color(0xFFEA4335) else Color(0xFFFBBC05)
                Text("⚠ ${callerReputation.reputationLevel.name.replace("_", " ")}", color = repColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("External Directory: ${callerReputation.displayName ?: callerReputation.category.name}", color = Color.LightGray, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (activeIncident != null) {
                Text(
                    text = "CRITICAL SECURITY INCIDENT\n${activeIncident.incidentType.name.replace("_", " ")}",
                    color = Color(0xFFEA4335),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                text = activeIncident?.explanation ?: riskAssessment?.attackContext?.explanation ?: riskAssessment?.explanation ?: "Multiple suspicious signals detected.",
                color = Color.White,
                textAlign = TextAlign.Center
            )

            if (activeIncident != null && activeIncident.recommendedActions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Recommended:", color = Color.Gray, fontWeight = FontWeight.Bold)
                activeIncident.recommendedActions.forEach { action ->
                    Text(action, color = Color.LightGray, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = Color.Gray)
            }
        }
    }
}

@Composable
fun FullScreenSecurityOverlay(
    callerIdentity: CallerIdentity?,
    callerReputation: com.trustmesh.app.core.identity.CallerReputation?,
    fallbackName: String,
    fallbackNumber: String,
    riskLevel: RiskLevel,
    riskAssessment: com.trustmesh.app.core.intelligence.risk.RiskAssessment?,
    activeIncident: com.trustmesh.app.core.incident.SecurityIncident?,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEE0B0D10))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🛡", fontSize = 48.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text("TRUSTMESH ALERT", color = Color(0xFFEA4335), fontSize = 24.sp, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(8.dp))
            Text(riskLevel.displayName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(24.dp))
            if (activeIncident != null) {
                Text(
                    text = activeIncident.incidentType.name.replace("_", " "),
                    color = Color(0xFFEA4335),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                text = activeIncident?.explanation ?: riskAssessment?.attackContext?.explanation ?: riskAssessment?.explanation ?: "This interaction has multiple strong indicators of potential fraud or abuse.",
                color = Color.White,
                textAlign = TextAlign.Center,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (activeIncident != null && activeIncident.recommendedActions.isNotEmpty()) {
                Text("Recommended:", color = Color.Gray, fontWeight = FontWeight.Bold)
                activeIncident.recommendedActions.forEach { action ->
                    Text("• $action", color = Color.LightGray, fontSize = 16.sp, textAlign = TextAlign.Center)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            HorizontalDivider(color = Color.DarkGray)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Evidence", color = Color.Gray, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                val evidenceList = riskAssessment?.evidence ?: listOf("Incoming call")
                for (ev in evidenceList.take(4)) {
                    Text("• $ev", color = Color.LightGray)
                }

                if (callerReputation != null && callerReputation.reputationLevel == com.trustmesh.app.core.identity.ReputationLevel.HIGH_RISK) {
                    Text("• External source reports HIGH RISK", color = Color(0xFFEA4335), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color.DarkGray)
            Spacer(modifier = Modifier.height(32.dp))
            TextButton(onClick = onDismiss) {
                Text("Dismiss Alert", color = Color.Gray, fontSize = 16.sp)
            }
        }
    }
}
