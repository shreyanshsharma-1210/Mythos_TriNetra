package com.trustmesh.app.ui.screens.home

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
import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.interaction.InteractionManager
import com.trustmesh.app.ui.components.InteractionCard
import com.trustmesh.app.ui.theme.*

@Composable
fun HomeScreen(onInteractionClick: (String) -> Unit) {
    val interactions by InteractionManager.interactions.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrustMeshBackground)
            .padding(16.dp)
    ) {
        // Protection Status
        Text("LOW RISK", style = Typography.titleLarge, color = RiskLow)
        Text("You're protected.", style = Typography.bodyLarge, color = TextPrimary)
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Recent Interactions", style = Typography.titleMedium, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        
        LazyColumn {
            items(interactions) { interaction ->
                InteractionCard(interaction = interaction, onClick = { onInteractionClick(interaction.id) })
            }
        }
    }
}
