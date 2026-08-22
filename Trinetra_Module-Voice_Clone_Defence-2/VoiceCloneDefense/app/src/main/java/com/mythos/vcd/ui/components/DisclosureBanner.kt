package com.mythos.vcd.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythos.vcd.service.DisclosureGate

/**
 * FR-VOICE-CAP-2. The exact disclosure wording, in a bar that cannot be scrolled away.
 *
 * Two things make this more than decoration:
 *
 *  1. It opens [DisclosureGate] when it draws and shuts it when it leaves the composition. The
 *     foreground service refuses to open the microphone while the gate is shut, so the banner is
 *     load-bearing — remove it and capture stops working, rather than capture continuing silently.
 *  2. It is marked as an accessibility live region, so a screen-reader user is told the call is
 *     being screened rather than only sighted users seeing it.
 */
@Composable
fun DisclosureBanner(
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    DisposableEffect(Unit) {
        DisclosureGate.markShown()
        onDispose { DisclosureGate.markHidden() }
    }

    val transition = rememberInfiniteTransition(label = "disclosure-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(BannerBackground)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics {
                liveRegion = LiveRegionMode.Assertive
                contentDescription =
                    "This call is being screened for fraud protection by TRINETRA. " +
                    "The microphone is active."
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(22.dp)
                .alpha(pulse),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = DISCLOSURE_TEXT,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                lineHeight = 20.sp,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
        androidx.compose.foundation.Canvas(Modifier.size(10.dp)) {
            drawCircle(color = Color.White.copy(alpha = pulse), radius = size.minDimension / 2f)
        }
    }
}

/**
 * A non-interlocking preview of the same bar, for screens that need to show the user what the
 * banner will look like without actually opening the capture gate.
 */
@Composable
fun DisclosureBannerPreviewOnly(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(BannerBackground.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Mic, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(
            text = DISCLOSURE_TEXT,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            lineHeight = 17.sp,
        )
    }
}

/** Kept in one constant so the wording cannot drift between the banner and its preview. */
const val DISCLOSURE_TEXT = "This call is being screened for fraud protection by TRINETRA"

private val BannerBackground = Color(0xFFB91C1C)

@Preview
@Composable
private fun DisclosureBannerPreview() {
    DisclosureBanner(subtitle = "Microphone active · speakerphone required")
}

@Suppress("unused")
private val circleShapeRef = CircleShape
