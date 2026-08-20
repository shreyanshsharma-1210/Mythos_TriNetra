package com.trustmesh.app.ui.screens.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trustmesh.app.interaction.InteractionManager
import com.trustmesh.app.core.incident.SecurityIncidentManager
import com.trustmesh.app.ui.components.InteractionCard
import com.trustmesh.app.ui.components.IncidentCard
import com.trustmesh.app.ui.theme.*

@Composable
fun HistoryScreen(onInteractionClick: (String) -> Unit) {
    val interactions by InteractionManager.interactions.collectAsState()
    val incidents by SecurityIncidentManager.incidents.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrustMeshBackground)
            .padding(16.dp)
    ) {
        Text("Interaction History", style = Typography.titleLarge, color = TextPrimary)
        Spacer(modifier = Modifier.height(16.dp))
        
        LazyColumn {
            if (incidents.isNotEmpty()) {
                item {
                    Text("SECURITY INCIDENTS", style = Typography.titleSmall, color = SecurityAccent, modifier = Modifier.padding(vertical = 8.dp))
                }
                items(incidents) { incident ->
                    IncidentCard(incident = incident, onClick = { onInteractionClick(incident.incidentId) })
                    Spacer(modifier = Modifier.height(8.dp))
                }
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            item {
                Text("INTERACTIONS", style = Typography.titleSmall, color = TextSecondary, modifier = Modifier.padding(vertical = 8.dp))
            }
            items(interactions) { interaction ->
                InteractionCard(interaction = interaction, onClick = { onInteractionClick(interaction.id) })
            }
        }
    }
}
