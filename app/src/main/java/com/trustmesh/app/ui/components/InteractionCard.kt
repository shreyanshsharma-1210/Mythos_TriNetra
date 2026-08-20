package com.trustmesh.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.trustmesh.app.interaction.Interaction
import com.trustmesh.app.ui.theme.*

@Composable
fun InteractionCard(interaction: Interaction, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(TrustMeshSurfaceElevated, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = interaction.title, style = Typography.titleMedium, color = TextPrimary)
            Text(text = interaction.timestamp, style = Typography.bodyLarge, color = TextSecondary)
        }
        
        if (!interaction.notificationTitle.isNullOrBlank() || !interaction.notificationText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            if (!interaction.notificationTitle.isNullOrBlank()) {
                Text(text = interaction.notificationTitle, style = Typography.titleMedium, color = TextPrimary)
            }
            if (!interaction.notificationText.isNullOrBlank()) {
                Text(text = interaction.notificationText, style = Typography.bodyLarge, color = TextPrimary)
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RiskBadge(riskLevel = interaction.riskLevel)
            
            interaction.riskAssessment?.attackContext?.let { context ->
                // Basic badge for intent
                Text(
                    text = context.inferredIntent.name.replace("_", " "),
                    modifier = Modifier
                        .background(Color(0x33FBBC05), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color(0xFFFBBC05),
                    style = Typography.bodySmall
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = interaction.riskAssessment?.attackContext?.explanation ?: interaction.summary, style = Typography.bodyLarge, color = TextSecondary)
    }
}
