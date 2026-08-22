package com.trustmesh.app.vcd.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trustmesh.app.vcd.pipeline.Level
import com.trustmesh.app.vcd.ui.theme.StatusColors
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Live input level meter.
 *
 * Shown wherever the app is recording, because "is it actually hearing anything" is the first
 * question a tester has and the one a silent failure hides. The scale is dBFS rather than raw
 * amplitude — linear amplitude spends almost its whole width on the top few dB and makes quiet
 * but perfectly usable speech look like silence.
 */
@Composable
fun LevelMeter(
    rms: Float,
    peak: Float,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val rmsDb = amplitudeToDb(rms)
    val peakDb = amplitudeToDb(peak)
    val rmsFraction by animateFloatAsState(dbToFraction(rmsDb), label = "rms")
    val peakFraction by animateFloatAsState(dbToFraction(peakDb), label = "peak")

    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val barColor = when {
        !active -> StatusColors.indeterminate
        peakDb > -1f -> StatusColors.critical      // clipping
        rmsDb < -50f -> StatusColors.suspicious    // too quiet to score reliably
        else -> StatusColors.safe
    }

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(track)
                .semantics {
                    contentDescription = if (!active) {
                        "Input level meter, capture inactive"
                    } else {
                        "Input level ${rmsDb.roundToInt()} decibels"
                    }
                }
        ) {
            Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                drawRect(
                    color = barColor,
                    size = Size(size.width * rmsFraction.coerceIn(0f, 1f), size.height),
                )
                // Peak tick, so a short transient that clipped is still visible after it passed.
                val x = (size.width * peakFraction.coerceIn(0f, 1f)).coerceIn(1f, size.width - 2f)
                drawRect(
                    color = Color.White.copy(alpha = 0.9f),
                    topLeft = Offset(x - 1f, 0f),
                    size = Size(2f, size.height),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (active) "${rmsDb.roundToInt()} dBFS RMS" else "not capturing",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Text(
                text = if (active) "peak ${peakDb.roundToInt()}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

private fun amplitudeToDb(a: Float): Float =
    if (a <= 1e-7f) -90f else max(-90f, 20f * log10(a))

/** Maps -60..0 dBFS onto 0..1; below -60 there is nothing worth showing. */
private fun dbToFraction(db: Float): Float = ((db + 60f) / 60f).coerceIn(0f, 1f)

/**
 * One named score with its value and threshold context.
 *
 * The threshold marker matters more than the bar: a similarity of 0.71 means nothing to a user
 * until they can see where the line was drawn, and showing the line is also what keeps the app
 * honest about these being provisional, uncalibrated cut-offs.
 */
@Composable
fun ScoreBar(
    label: String,
    value: Float?,
    threshold: Float,
    thresholdLabel: String,
    highIsBad: Boolean,
    modifier: Modifier = Modifier,
) {
    val fraction by animateFloatAsState(value ?: 0f, label = "score-$label")
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val color = when {
        value == null -> StatusColors.indeterminate
        highIsBad && value >= threshold -> StatusColors.critical
        !highIsBad && value >= threshold -> StatusColors.safe
        !highIsBad -> StatusColors.suspicious
        else -> StatusColors.safe
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(
                text = value?.let { String.format("%.3f", it) } ?: "—",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = color,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(track)
        ) {
            Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                drawRect(color = color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
                val x = (size.width * threshold).coerceIn(1f, size.width - 2f)
                drawRect(
                    color = MaterialTheme.let { Color.Black.copy(alpha = 0.45f) },
                    topLeft = Offset(x - 1f, 0f),
                    size = Size(2f, size.height),
                )
            }
        }
        Text(
            text = thresholdLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Compact status chip used on the home screen and in list rows. */
@Composable
fun StatusChip(level: Level, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(StatusColors.accent(level).copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = StatusColors.label(level),
            color = StatusColors.accent(level),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}
