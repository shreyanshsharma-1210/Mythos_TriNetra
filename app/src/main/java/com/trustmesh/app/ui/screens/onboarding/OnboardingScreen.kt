package com.trustmesh.app.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trustmesh.app.ui.theme.*

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val step = remember { mutableStateOf(0) }
    val steps = listOf(
        Pair("Meet TrustMesh", "Security intelligence for everyday interactions."),
        Pair("TrustMesh doesn't listen.", "TrustMesh does not record or analyze call audio."),
        Pair("Notification Monitoring", "TrustMesh can observe notifications posted by apps on your device to understand security context.\n\nTrustMesh does not send notification content to a cloud service.\nNotification access is controlled by Android and can be disabled at any time."),
        Pair("TrustMesh watches for context.", "Calls, notifications, app activity and other available security signals are correlated to identify risky interactions."),
        Pair("Protection when it matters.", "TrustMesh stays quiet when everything looks normal and becomes more visible when risk increases."),
        Pair("Set up protection", "Begin capability setup.")
    )
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrustMeshBackground)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val currentStep = steps[step.value]
        Text(currentStep.first, style = Typography.titleLarge, color = SecurityAccent)
        Spacer(modifier = Modifier.height(16.dp))
        Text(currentStep.second, style = Typography.bodyLarge, color = TextPrimary)
        
        Spacer(modifier = Modifier.height(64.dp))
        Button(
            onClick = {
                if (step.value < steps.size - 1) step.value++ else onFinish()
            },
            colors = ButtonDefaults.buttonColors(containerColor = SecurityAccent, contentColor = TrustMeshBackground)
        ) {
            Text(if (step.value < steps.size - 1) "Next" else "Finish Setup")
        }
    }
}
