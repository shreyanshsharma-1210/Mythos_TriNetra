package com.trustmesh.app.vcd.ui.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trustmesh.app.vcd.ui.components.DisclosureBannerPreviewOnly

/**
 * FR-VOICE-CAP-4: permission is requested during onboarding with an explanation, never sprung on
 * the user mid-call. The screen also states the scope boundary in plain language, because "this
 * app wants your microphone" and "this app records phone calls" are very different things and the
 * user is entitled to know which one this is.
 */
@Composable
fun PermissionScreen(onDone: () -> Unit, onTestMode: () -> Unit) {
    val permission = rememberMicPermissionState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Microphone access", style = MaterialTheme.typography.headlineMedium)

        Text(
            "Voice Clone Defense listens through the ordinary microphone while a call is on " +
                "speakerphone — the same sound anyone in the room can hear.",
            style = MaterialTheme.typography.bodyLarge,
        )

        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("What it does not do", fontWeight = FontWeight.Bold)
                Bullet("It cannot tap the cellular call itself. Android reserves those audio sources for system apps, and this app does not request them.")
                Bullet("It never records without telling you. A banner appears on screen the moment the microphone opens, and stays for as long as it is open.")
                Bullet("It keeps no recordings. Audio lives in memory only long enough to be analysed, then it is gone.")
                Bullet("It sends nothing anywhere. The app has no internet permission at all, so audio physically cannot leave the device.")
            }
        }

        Text("This is what you will see whenever it is listening:", style = MaterialTheme.typography.bodyMedium)
        DisclosureBannerPreviewOnly()

        Spacer(Modifier.height(4.dp))

        when {
            permission.granted -> {
                Text(
                    "Microphone access is granted. Live verification is available.",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }

            permission.permanentlyDenied -> {
                Text(
                    "Microphone access is turned off for this app, and Android will not ask " +
                        "again from here. You can turn it on in Settings.",
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = permission::openSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Open app settings")
                }
                DegradedNotice(onTestMode)
            }

            else -> {
                Button(onClick = permission::request, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow microphone access")
                }
                DegradedNotice(onTestMode)
            }
        }

        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
    }
}

/** Phase 6: without the microphone the app is reduced, not broken. Say so, and offer the way on. */
@Composable
private fun DegradedNotice(onTestMode: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Without microphone access", fontWeight = FontWeight.Bold)
            Text(
                "Live verification during a call will not work. Everything else still does — you " +
                    "can enrol a voice from an audio file and run clips through the full " +
                    "analysis pipeline in Test Mode.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onTestMode, modifier = Modifier.fillMaxWidth()) {
                Text("Go to Test Mode")
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Text("•  $text", style = MaterialTheme.typography.bodyMedium)
}
