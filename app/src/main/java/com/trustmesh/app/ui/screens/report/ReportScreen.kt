package com.trustmesh.app.ui.screens.report

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trustmesh.app.interaction.InteractionManager
import com.trustmesh.app.core.incident.SecurityIncidentManager
import com.trustmesh.app.core.incident.IncidentStatus
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.trustmesh.app.ui.components.RiskBadge
import com.trustmesh.app.ui.components.CallerRiskCard
import com.trustmesh.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.material3.Scaffold
import com.trustmesh.app.ui.components.TrustMeshTopBar

@Composable
fun ReportScreen(interactionId: String, onBackClick: () -> Unit = {}) {
    val interactions by InteractionManager.interactions.collectAsState()
    val incidents by SecurityIncidentManager.incidents.collectAsState()
    
    val incident = incidents.find { it.incidentId == interactionId }
    val interaction = interactions.find { it.id == interactionId }
    
    Scaffold(
        topBar = {
            TrustMeshTopBar(title = "Trinetra", onBackClick = onBackClick)
        }
    ) { paddingValues ->
        if (incident != null) {
            IncidentReportScreen(incident = incident, modifier = Modifier.padding(paddingValues))
            return@Scaffold
        }

        if (interaction == null) {
            // Simple fallback
            Text("Report not found", color = OnboardingText, modifier = Modifier.fillMaxSize().background(OnboardingBackground).padding(paddingValues).padding(16.dp))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(OnboardingBackground)
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Text(interaction.title, style = Typography.titleLarge, color = OnboardingText)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RiskBadge(interaction.riskLevel)
            
            interaction.riskAssessment?.attackContext?.let { context ->
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = androidx.compose.ui.graphics.Color(0x33FBBC05),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        text = context.inferredIntent.name.replace("_", " "),
                        color = androidx.compose.ui.graphics.Color(0xFFFBBC05),
                        style = Typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        
        interaction.riskAssessment?.attackContext?.let { context ->
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                color = androidx.compose.ui.graphics.Color.White,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                shadowElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Correlated Attack Context", style = Typography.titleSmall, color = SecurityAccent)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(context.explanation, style = Typography.bodyMedium, color = OnboardingText)
                }
            }
        }
        
        if (!interaction.associatedKey.isNullOrEmpty()) {
            Text("Source", style = Typography.titleSmall, color = SecurityAccent)
            Text(interaction.appName ?: "Unknown App", style = Typography.bodyLarge, color = OnboardingText)
            Spacer(modifier = Modifier.height(12.dp))
            
            Text("Package", style = Typography.titleSmall, color = SecurityAccent)
            Text(interaction.packageName ?: "Unknown Package", style = Typography.bodyLarge, color = OnboardingText)
            Spacer(modifier = Modifier.height(12.dp))
            
            if (!interaction.notificationTitle.isNullOrBlank()) {
                Text("Notification", style = Typography.titleSmall, color = SecurityAccent)
                Text(interaction.notificationTitle, style = Typography.bodyLarge, color = OnboardingText)
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            if (!interaction.notificationText.isNullOrBlank()) {
                Text("Content", style = Typography.titleSmall, color = SecurityAccent)
                Text(interaction.notificationText, style = Typography.bodyLarge, color = OnboardingText)
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            Text("Observed", style = Typography.titleSmall, color = SecurityAccent)
            Text(interaction.timestamp, style = Typography.bodyLarge, color = OnboardingText)
            Spacer(modifier = Modifier.height(24.dp))
        }
        
        Text("Observed signals:", style = Typography.titleMedium, color = OnboardingText)
        interaction.evidence.forEach { evidence ->
            Text("• $evidence", style = Typography.bodyLarge, color = OnboardingTextSecondary)
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("Timeline:", style = Typography.titleMedium, color = OnboardingText)
        interaction.timeline.forEach { timelineEntry ->
            Text(timelineEntry, style = Typography.bodyLarge, color = OnboardingTextSecondary)
        }
    }
}
}

@Composable
fun IncidentReportScreen(incident: com.trustmesh.app.core.incident.SecurityIncident, modifier: Modifier = Modifier) {
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OnboardingBackground)
            .padding(16.dp)
    ) {
        Text("SECURITY INCIDENTS", style = Typography.labelMedium, color = SecurityAccent)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Incident type:\n${incident.incidentType.name.replace("_", " ")}", style = Typography.titleLarge, color = OnboardingText)
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Severity:", style = Typography.titleSmall, color = OnboardingTextSecondary)
            RiskBadge(incident.severity)
        }
        Spacer(modifier = Modifier.height(8.dp))
        
        Text("Risk: ${incident.riskScore} / 100", style = Typography.titleSmall, color = OnboardingTextSecondary)
        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Why TriNetra flagged this:", style = Typography.titleMedium, color = OnboardingText)
        Text(incident.explanation, style = Typography.bodyLarge, color = OnboardingTextSecondary)
        Spacer(modifier = Modifier.height(16.dp))
        
        if (incident.callerIdentity != null) {
            Text("Caller:", style = Typography.titleMedium, color = OnboardingText)
            Text(incident.callerIdentity.displayName ?: incident.callerIdentity.phoneNumber, style = Typography.bodyLarge, color = OnboardingTextSecondary)
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        if (incident.callerReputation != null) {
            Text("Reputation:", style = Typography.titleMedium, color = OnboardingText)
            Text("${incident.callerReputation.source} — ${incident.callerReputation.reputationLevel.name}", style = Typography.bodyLarge, color = OnboardingTextSecondary)
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        Text("Evidence Timeline:", style = Typography.titleMedium, color = OnboardingText)
        val interactions by InteractionManager.interactions.collectAsState()
        val relatedInteractions = interactions.filter { it.id in incident.relatedInteractionIds }
        
        relatedInteractions.forEach { inter ->
            Text("${timeFormat.format(Date(inter.timestampMs))}", style = Typography.labelSmall, color = OnboardingTextSecondary)
            Text(inter.summary, style = Typography.bodyLarge, color = OnboardingText)
            Spacer(modifier = Modifier.height(4.dp))
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Recommended actions:", style = Typography.titleMedium, color = OnboardingText)
        incident.recommendedActions.forEach { action ->
            Text("• $action", style = Typography.bodyLarge, color = OnboardingTextSecondary)
        }
        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Status: ${incident.status.name}", style = Typography.titleSmall, color = OnboardingText)
        Spacer(modifier = Modifier.height(24.dp))
        
        if (incident.status == IncidentStatus.ACTIVE) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { SecurityIncidentManager.resolveIncident(incident.incidentId) },
                    colors = ButtonDefaults.buttonColors(containerColor = SecurityAccent)
                ) {
                    Text("Resolve Incident", color = androidx.compose.ui.graphics.Color.White)
                }
                
                Button(
                    onClick = { SecurityIncidentManager.dismissIncident(incident.incidentId) },
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.White)
                ) {
                    Text("Dismiss Incident", color = OnboardingText)
                }
            }
        }
    }
}
