package com.trustmesh.app.ui.screens.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trustmesh.app.ui.theme.*

// ────────────────────────────────────────────────────────────────────────────
// Progress indicator: six subtle pill segments
// ────────────────────────────────────────────────────────────────────────────

@Composable
fun TrinetraProgressIndicator(
    currentStep: Int,     // 1-indexed
    totalSteps: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 1..totalSteps) {
            val isActive = i == currentStep
            val isPast   = i < currentStep
            val color = when {
                isActive -> OnboardingProgressActive
                isPast   -> OnboardingProgressActive.copy(alpha = 0.35f)
                else     -> OnboardingProgressInactive
            }
            val width: Dp = if (isActive) 32.dp else 16.dp

            val animatedWidth by animateDpAsState(
                targetValue = width,
                animationSpec = tween(durationMillis = 300, easing = EaseInOut),
                label = "progress_width_$i"
            )
            val animatedColor by animateColorAsState(
                targetValue = color,
                animationSpec = tween(durationMillis = 300),
                label = "progress_color_$i"
            )

            Box(
                modifier = Modifier
                    .width(animatedWidth)
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(animatedColor)
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Top meta bar: "1/6" + "Skip"
// ────────────────────────────────────────────────────────────────────────────

@Composable
fun TrinetraTopMeta(
    currentStep: Int,
    totalSteps: Int,
    onSkip: () -> Unit,
    showSkip: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$currentStep/$totalSteps",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = OnboardingText.copy(alpha = 0.65f),
            fontFamily = TrinetraFontFamily
        )

        if (showSkip) {
            Text(
                text = "Skip",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = OnboardingText.copy(alpha = 0.65f),
                fontFamily = TrinetraFontFamily,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSkip() }
                    .padding(vertical = 4.dp, horizontal = 4.dp)
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Primary dark button
// ────────────────────────────────────────────────────────────────────────────

@Composable
fun TrinetraPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isWide: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val bgColor by animateColorAsState(
        targetValue = if (isPressed) Color(0xFF232B3C) else OnboardingButtonBg,
        animationSpec = tween(150),
        label = "button_color"
    )

    // Using a subtle elevation shadow to lift the button off the off-white background
    androidx.compose.material3.Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(28.dp),
        color = bgColor,
        shadowElevation = 2.dp,
        modifier = modifier
            .then(if (isWide) Modifier.fillMaxWidth() else Modifier.wrapContentWidth())
            .height(56.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = if (isWide) 24.dp else 30.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = OnboardingButtonText,
                fontFamily = TrinetraFontFamily,
                letterSpacing = 0.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "→",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = OnboardingButtonText,
                fontFamily = TrinetraFontFamily
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Illustration wrapper — centers the image and constraints its size responsively
// ────────────────────────────────────────────────────────────────────────────

@Composable
fun TrinetraIllustration(
    illustrationRes: Int,
    aspectRatio: Float,
    availableWidthFraction: Float = 0.55f,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val imgWidth = maxWidth * availableWidthFraction
        val imgHeight = imgWidth / aspectRatio.coerceAtLeast(0.1f)

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = illustrationRes),
                contentDescription = null,
                modifier = Modifier
                    .width(imgWidth)
                    .height(imgHeight)
            )
        }
    }
}
