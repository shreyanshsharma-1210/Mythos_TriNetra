package com.trustmesh.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.ui.theme.*

@Composable
fun CallerRiskCard(identity: String, relationship: String, riskLevel: RiskLevel, reason: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(TrustMeshSurfaceElevated, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(identity, style = Typography.titleLarge, color = TextPrimary)
        Text(relationship, style = Typography.bodyLarge, color = TextSecondary)
        Spacer(modifier = Modifier.height(16.dp))
        RiskBadge(riskLevel)
        Spacer(modifier = Modifier.height(8.dp))
        Text(reason, style = Typography.bodyLarge, color = TextPrimary)
    }
}
