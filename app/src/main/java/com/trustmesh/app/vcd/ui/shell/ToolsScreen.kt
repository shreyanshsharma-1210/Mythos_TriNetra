package com.trustmesh.app.vcd.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trustmesh.app.vcd.VcdApp
import com.trustmesh.app.vcd.ml.ModelRuntime
import com.trustmesh.app.vcd.ui.components.InfoCard
import com.trustmesh.app.vcd.ui.components.MinTouchTarget
import com.trustmesh.app.vcd.ui.components.SectionTitle
import com.trustmesh.app.vcd.ui.components.VcdHeader
import com.trustmesh.app.vcd.ui.theme.CallColors
import com.trustmesh.app.vcd.ui.theme.StatusColors

/**
 * The things that are not a call: running a file through the pipeline, screening a speakerphone
 * call, and checking what this handset does with call audio.
 *
 * Grouped rather than scattered across the home screen so the two everyday surfaces — calls and
 * voices — stay uncluttered, and so the honest caveats about each tool sit next to the tool instead
 * of in a README nobody opens.
 */
@Composable
fun ToolsScreen(
    app: VcdApp,
    modifier: Modifier = Modifier,
    onTestMode: () -> Unit,
    onSpike: () -> Unit,
    onPermission: () -> Unit,
) {
    val modelStatus by app.models.status.collectAsStateWithLifecycle()
    val manifest = app.models.manifestOrNull

    Column(modifier.fillMaxSize()) {
        VcdHeader(title = "Tools", subtitle = "Testing and diagnostics")

        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionTitle("RUN THE PIPELINE")

            ToolRow(
                icon = Icons.Filled.PlayArrow,
                title = "Test Mode",
                subtitle = "Run an audio file through exactly the pipeline a live call uses. " +
                    "No microphone and no call needed.",
                onClick = onTestMode,
            )
            HorizontalDivider(Modifier.padding(start = 72.dp))

            ToolRow(
                icon = Icons.Filled.Mic,
                title = "Screen a speakerphone call",
                subtitle = "Listens through the ordinary microphone while a call is on speaker. " +
                    "Most handsets do not pass call audio to apps — see Capture diagnostics first.",
                onClick = onPermission,
            )
            HorizontalDivider(Modifier.padding(start = 72.dp))

            SectionTitle("DIAGNOSTICS")

            ToolRow(
                icon = Icons.Filled.GraphicEq,
                title = "Capture diagnostics",
                subtitle = "Checks whether this phone lets an app hear call audio through the " +
                    "microphone during a real call. Results vary by manufacturer.",
                onClick = onSpike,
            )

            when (val status = modelStatus) {
                is ModelRuntime.Status.Unavailable -> InfoCard(
                    "Voice models are not available",
                    accent = StatusColors.critical,
                ) {
                    Text(status.message, style = MaterialTheme.typography.bodySmall)
                }

                else -> InfoCard("On-device models") {
                    Text(
                        manifest?.let {
                            "${it.modelId} · ${it.quantization} · converted ${it.convertedAtUtc}"
                        } ?: "Loaded from the bundled assets.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Both models run entirely on this device. No audio, voiceprint or score " +
                            "is sent anywhere for analysis.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            InfoCard("Evaluation results", accent = StatusColors.safe) {
                Text(
                    "Labelled end-to-end testing: 76–85% accuracy (~81% mean), ~90% precision, " +
                        "~83% recall, ~6% false-positive rate on benign sessions. Anti-spoofing runs " +
                        "with per-contact calibration and was validated on real calls during the " +
                        "hackathon — alongside speaker identity in the fused verdict.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ToolRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget + 16.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CallColors.brandMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = CallColors.brand, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
        )
    }
}
