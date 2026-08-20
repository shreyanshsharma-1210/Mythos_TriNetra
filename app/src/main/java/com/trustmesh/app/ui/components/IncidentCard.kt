package com.trustmesh.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trustmesh.app.core.incident.IncidentStatus
import com.trustmesh.app.core.incident.SecurityIncident
import com.trustmesh.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun IncidentCard(incident: SecurityIncident, onClick: () -> Unit) {
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val timeString = timeFormat.format(Date(incident.createdAt))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TrustMeshSurface)
            .clickable { onClick() }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val title = incident.incidentType.name.replace("_", " ")
            Text(title, style = Typography.titleMedium, color = TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RiskBadge(incident.severity)
                if (incident.status != IncidentStatus.ACTIVE) {
                    Text(
                        text = incident.status.name,
                        style = Typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = TextSecondary
                    )
                }
            }
        }
        Text(timeString, style = Typography.bodyMedium, color = TextSecondary)
    }
}
