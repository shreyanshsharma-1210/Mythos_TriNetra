package com.trustmesh.app.ui.screens.protection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trustmesh.app.ui.theme.*

@Composable
fun ProtectionScreen(
    callerName: String,
    callerNumber: String,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TrustMeshBackground.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(24.dp))
                .background(TrustMeshSurfaceElevated)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "TriNetra",
                style = Typography.titleMedium,
                color = SecurityAccent
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "INCOMING CALL",
                style = Typography.labelLarge,
                color = TextSecondary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = callerName,
                style = Typography.headlineMedium,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            
            if (callerNumber.isNotEmpty() && callerNumber != callerName) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = callerNumber,
                    style = Typography.titleMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(RiskLow)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "PROTECTION ACTIVE",
                    style = Typography.labelLarge,
                    color = RiskLow
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Monitoring this interaction",
                style = Typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(TrustMeshSurface)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "LOW RISK",
                        style = Typography.titleSmall,
                        color = RiskLow
                    )
                    Text(
                        text = "Initial observation",
                        style = Typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = TrustMeshSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "Dismiss",
                    color = TextPrimary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}
