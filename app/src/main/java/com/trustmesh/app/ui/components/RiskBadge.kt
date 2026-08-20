package com.trustmesh.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.ui.theme.*

@Composable
fun RiskBadge(riskLevel: RiskLevel) {
    val color = when (riskLevel) {
        RiskLevel.LOW -> RiskLow
        RiskLevel.ELEVATED -> RiskElevated
        RiskLevel.HIGH -> RiskHigh
        RiskLevel.CRITICAL -> RiskCritical
    }
    Text(
        text = riskLevel.displayName,
        color = TrustMeshBackground,
        style = Typography.labelLarge,
        modifier = Modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
