package com.trustmesh.app.ui.screens.onboarding

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trustmesh.app.R
import com.trustmesh.app.ui.theme.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity

// ────────────────────────────────────────────────────────────────────────────
// Floating animation container using graphicsLayer to avoid recompositions
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun FloatingContainer(
    durationMillis: Int = 3000,
    maxTranslationY: Float = 6f,
    maxTranslationX: Float = 0f,
    maxRotation: Float = 0f,
    scaleRange: ClosedFloatingPointRange<Float>? = null,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "floating")
    
    val translationY by infiniteTransition.animateFloat(
        initialValue = -maxTranslationY,
        targetValue = maxTranslationY,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "translation_y"
    )

    val translationX by if (maxTranslationX != 0f) {
        infiniteTransition.animateFloat(
            initialValue = -maxTranslationX,
            targetValue = maxTranslationX,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis + 400, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse
            ),
            label = "translation_x"
        )
    } else {
        remember { mutableStateOf(0f) }
    }
    
    val rotation by if (maxRotation != 0f) {
        infiniteTransition.animateFloat(
            initialValue = -maxRotation,
            targetValue = maxRotation,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis + 800, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse
            ),
            label = "rotation"
        )
    } else {
        remember { mutableStateOf(0f) }
    }
    
    val scale by if (scaleRange != null) {
        infiniteTransition.animateFloat(
            initialValue = scaleRange.start,
            targetValue = scaleRange.endInclusive,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis - 200, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
    } else {
        remember { mutableStateOf(1f) }
    }
    
    Box(
        modifier = modifier
            .graphicsLayer {
                this.translationY = translationY.dp.toPx()
                this.translationX = translationX.dp.toPx()
                this.rotationZ = rotation
                this.scaleX = scale
                this.scaleY = scale
            },
        content = content
    )
}

// ────────────────────────────────────────────────────────────────────────────
// Entry Animation wrapper for page entrance (fade + vertical translation)
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun EntryAnimationContainer(
    content: @Composable (entryProgress: Float) -> Unit
) {
    var animateTrigger by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        animateTrigger = true
    }
    val entryProgress by animateFloatAsState(
        targetValue = if (animateTrigger) 1f else 0f,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "page_entry"
    )
    content(entryProgress)
}

// ────────────────────────────────────────────────────────────────────────────
// Floating particles overlay on Canvas
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun BoxScope.FloatingParticlesOverlay(count: Int, seed: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "particles")
    val animVal by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000 + seed * 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "angle"
    )
    
    Canvas(modifier = Modifier.fillMaxSize().align(Alignment.Center)) {
        val center = Offset(size.width / 2, size.height / 2)
        val radiusBase = size.width * 0.36f
        
        for (i in 0 until count) {
            val angleRad = Math.toRadians((animVal + (i * 360f / count) + (seed * 60)).toDouble())
            val radius = radiusBase + (12f * kotlin.math.sin(angleRad * 2)).toFloat()
            val px = center.x + radius * kotlin.math.cos(angleRad).toFloat()
            val py = center.y + radius * kotlin.math.sin(angleRad).toFloat()
            
            drawCircle(
                color = if (i % 2 == 0) Color(0x3A667DFF) else Color(0x2411182D),
                radius = (3 + (i % 2) * 1.5f).dp.toPx(),
                center = Offset(px, py)
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Parallax and horizontal slide transition extension modifier
// ────────────────────────────────────────────────────────────────────────────
@Composable
private fun Modifier.onboardingTransition(
    entryProgress: Float,
    pageOffset: Float,
    isText: Boolean,
    screenWidth: Float = 0f,
    parallaxFactor: Float = 0f
): Modifier {
    val density = LocalDensity.current
    return this.graphicsLayer {
        val absOffset = kotlin.math.abs(pageOffset)
        val fade = (1f - absOffset).coerceIn(0f, 1f)
        
        alpha = entryProgress * fade
        
        if (isText) {
            // Text horizontal slide: -20dp to the left, +24dp from the right
            val textSlidePx = if (pageOffset < 0f) {
                pageOffset * with(density) { 20.dp.toPx() }
            } else {
                pageOffset * with(density) { 24.dp.toPx() }
            }
            translationX = textSlidePx
            translationY = with(density) { 8.dp.toPx() * (1f - entryProgress) }
        } else {
            // Illustration parallax translation:
            // Background field net speed: 0.15x -> parallaxFactor = -0.85f
            // Orbital lines net speed: 0.5x -> parallaxFactor = -0.5f
            // Central object net speed: 0.8x -> parallaxFactor = -0.2f
            // Particles net speed: 1.0x -> parallaxFactor = 0f
            translationX = parallaxFactor * pageOffset * screenWidth
            
            // Illustration scale: 0.94f to 1.0f
            val scale = 1f - 0.06f * absOffset
            scaleX = scale
            scaleY = scale
        }
    }
}

/**
 * Renders a highly customized composition depending on the current onboarding page step.
 * This guarantees unique layouts, asymmetry, and intentional vertical rhythm per screen.
 */
@Composable
fun OnboardingPage(
    page: OnboardingPageData,
    pageOffset: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Top
    ) {
        when (page.step) {
            1 -> ScreenOneMeet(pageOffset)
            2 -> ScreenTwoNoAudio(pageOffset)
            3 -> ScreenThreeContext(pageOffset)
            4 -> ScreenFourSignals(pageOffset)
            5 -> ScreenFiveAdaptive(pageOffset)
            6 -> ScreenSixSetup(pageOffset)
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Screen 1: Meet Trinetra (headline → large eye illustration)
// ────────────────────────────────────────────────────────────────────────────
@Composable
private fun ColumnScope.ScreenOneMeet(pageOffset: Float) {
    val screenWidth = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    
    EntryAnimationContainer { entryProgress ->
        Spacer(modifier = Modifier.weight(0.12f))
        
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .onboardingTransition(entryProgress, pageOffset, isText = true)
        ) {
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(60.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x1811182D))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Meet\nTrinetra.",
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 50.sp,
                letterSpacing = (-0.5).sp,
                color = OnboardingText,
                fontFamily = TrinetraFontFamily
            )
        }
        
        Spacer(modifier = Modifier.height(14.dp))
        
        Text(
            text = "Security intelligence for\neveryday interactions.",
            fontSize = 16.sp,
            lineHeight = 24.sp,
            color = OnboardingTextSecondary,
            fontWeight = FontWeight.Normal,
            fontFamily = TrinetraFontFamily,
            modifier = Modifier
                .padding(start = 16.dp)
                .onboardingTransition(entryProgress, pageOffset, isText = true)
        )
        
        Spacer(modifier = Modifier.weight(1.0f))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center
        ) {
            // Layer 1 & 2: Background fields (0.15x speed -> parallaxFactor = -0.85f)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, -0.85f)
            ) {
                val center = Offset(size.width / 2, size.height / 2)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x14667DFF), Color.Transparent),
                        center = center,
                        radius = size.width * 0.45f
                    ),
                    radius = size.width * 0.45f
                )
                drawCircle(
                    color = Color(0x0C667DFF),
                    radius = size.width * 0.35f,
                    center = Offset(center.x + 30f, center.y - 20f)
                )
            }
            
            // Layer 3 & 5: Orbital lines (0.5x speed -> parallaxFactor = -0.5f)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, -0.5f)
            ) {
                val center = Offset(size.width / 2, size.height / 2)
                drawCircle(
                    color = Color(0x1F667DFF),
                    radius = size.width * 0.4f,
                    style = Stroke(width = 1.dp.toPx())
                )
                drawCircle(
                    color = Color(0x0F11182D),
                    radius = size.width * 0.28f,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            
            // Layer 4: Central Trinetra eye object (0.8x speed -> parallaxFactor = -0.2f)
            FloatingContainer(
                durationMillis = 2800,
                maxTranslationY = 8f,
                modifier = Modifier
                    .size(280.dp)
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, -0.2f)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.trinetra_01_meet),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            // Layer 6: Floating particles overlay (1.0x speed -> parallaxFactor = 0f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, 0f)
            ) {
                FloatingParticlesOverlay(count = 4, seed = 1)
            }
        }
        
        Spacer(modifier = Modifier.weight(1.3f))
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Screen 2: Conversations stay yours (headline → wide waveform composition)
// ────────────────────────────────────────────────────────────────────────────
@Composable
private fun ColumnScope.ScreenTwoNoAudio(pageOffset: Float) {
    val screenWidth = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    
    EntryAnimationContainer { entryProgress ->
        Spacer(modifier = Modifier.weight(0.12f))
        
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .onboardingTransition(entryProgress, pageOffset, isText = true)
        ) {
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(84.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x1811182D))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Your\nconversations\nstay yours.",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 46.sp,
                letterSpacing = (-0.5).sp,
                color = OnboardingText,
                fontFamily = TrinetraFontFamily
            )
        }
        
        Spacer(modifier = Modifier.height(14.dp))
        
        Text(
            text = "Trinetra doesn't record\nor analyze call audio.",
            fontSize = 16.sp,
            lineHeight = 24.sp,
            color = OnboardingTextSecondary,
            fontWeight = FontWeight.Normal,
            fontFamily = TrinetraFontFamily,
            modifier = Modifier
                .padding(start = 16.dp)
                .onboardingTransition(entryProgress, pageOffset, isText = true)
        )
        
        Spacer(modifier = Modifier.weight(0.9f))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center
        ) {
            // Layer 1 & 2: Background fields (0.15x speed -> parallaxFactor = -0.85f)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, -0.85f)
            ) {
                val center = Offset(size.width / 2, size.height / 2)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x0E667DFF), Color.Transparent),
                        center = center,
                        radius = size.width * 0.48f
                    ),
                    radius = size.width * 0.48f
                )
                drawOval(
                    color = Color(0x0A667DFF),
                    topLeft = Offset(size.width * 0.1f, size.height * 0.22f),
                    size = Size(size.width * 0.8f, size.height * 0.56f)
                )
            }
            
            // Layer 3: Waveform traces (0.5x speed -> parallaxFactor = -0.5f)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, -0.5f)
            ) {
                val startY = size.height * 0.5f
                val path1 = Path().apply {
                    moveTo(size.width * 0.08f, startY)
                    cubicTo(
                        size.width * 0.25f, startY - 70f,
                        size.width * 0.35f, startY + 75f,
                        size.width * 0.5f, startY
                    )
                    cubicTo(
                        size.width * 0.65f, startY - 75f,
                        size.width * 0.75f, startY + 70f,
                        size.width * 0.92f, startY
                    )
                }
                drawPath(
                    path = path1,
                    color = Color(0x1F667DFF),
                    style = Stroke(width = 1.5.dp.toPx())
                )
                
                val startY2 = size.height * 0.46f
                val path2 = Path().apply {
                    moveTo(size.width * 0.1f, startY2)
                    cubicTo(
                        size.width * 0.28f, startY2 + 50f,
                        size.width * 0.38f, startY2 - 60f,
                        size.width * 0.52f, startY2
                    )
                    cubicTo(
                        size.width * 0.62f, startY2 + 60f,
                        size.width * 0.72f, startY2 - 50f,
                        size.width * 0.9f, startY2
                    )
                }
                drawPath(
                    path = path2,
                    color = Color(0x0A11182D),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            
            // Layer 4: Primary Waveform (0.8x speed -> parallaxFactor = -0.2f)
            FloatingContainer(
                durationMillis = 3200,
                maxTranslationY = 6f,
                maxRotation = 1f,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .aspectRatio(1f)
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, -0.2f)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.trinetra_02_no_audio),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            // Layer 6: Floating particles overlay (1.0x speed -> parallaxFactor = 0f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, 0f)
            ) {
                FloatingParticlesOverlay(count = 3, seed = 2)
            }
        }
        
        Spacer(modifier = Modifier.weight(1.4f))
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Screen 3: Notices little things (headline → orbital planetary system)
// ────────────────────────────────────────────────────────────────────────────
@Composable
private fun ColumnScope.ScreenThreeContext(pageOffset: Float) {
    val screenWidth = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    
    EntryAnimationContainer { entryProgress ->
        Spacer(modifier = Modifier.weight(0.12f))
        
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .onboardingTransition(entryProgress, pageOffset, isText = true)
        ) {
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(84.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x1811182D))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "It notices\nthe little\nthings.",
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 48.sp,
                letterSpacing = (-0.5).sp,
                color = OnboardingText,
                fontFamily = TrinetraFontFamily
            )
        }
        
        Spacer(modifier = Modifier.height(14.dp))
        
        Text(
            text = "Notifications, apps and\nactivity help us understand\ncontext.",
            fontSize = 16.sp,
            lineHeight = 24.sp,
            color = OnboardingTextSecondary,
            fontWeight = FontWeight.Normal,
            fontFamily = TrinetraFontFamily,
            modifier = Modifier
                .padding(start = 16.dp)
                .onboardingTransition(entryProgress, pageOffset, isText = true)
        )
        
        Spacer(modifier = Modifier.weight(1.1f))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "planetary")
            val orbitAngle by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(8000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "orbit_angle"
            )

            // Layer 1 & 2: Background fields (0.15x speed -> parallaxFactor = -0.85f)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, -0.85f)
            ) {
                val center = Offset(size.width / 2, size.height / 2)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x12667DFF), Color.Transparent),
                        center = center,
                        radius = size.width * 0.45f
                    ),
                    radius = size.width * 0.45f
                )
                drawCircle(
                    color = Color(0x0A667DFF),
                    radius = size.width * 0.32f,
                    center = Offset(center.x - 20f, center.y + 20f)
                )
            }
            
            // Layer 3: Multiple orbital paths (0.5x speed -> parallaxFactor = -0.5f)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, -0.5f)
            ) {
                val center = Offset(size.width / 2, size.height / 2)
                rotate(degrees = 15f, pivot = center) {
                    drawOval(
                        color = Color(0x1F667DFF),
                        topLeft = Offset(center.x - size.width * 0.38f, center.y - size.height * 0.12f),
                        size = Size(size.width * 0.76f, size.height * 0.24f),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
                rotate(degrees = -30f, pivot = center) {
                    drawOval(
                        color = Color(0x1211182D),
                        topLeft = Offset(center.x - size.width * 0.2f, center.y - size.height * 0.4f),
                        size = Size(size.width * 0.4f, size.height * 0.8f),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }

            // Layer 4: Central VectorDrawable (0.8x speed -> parallaxFactor = -0.2f)
            FloatingContainer(
                durationMillis = 3500,
                maxTranslationY = 5f,
                scaleRange = 0.98f..1.02f,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .aspectRatio(1f)
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, -0.2f)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.trinetra_03_context),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Layer 6: Draw the three contextual particles on custom orbits (1.0x speed -> parallaxFactor = 0f)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, 0f)
            ) {
                val center = Offset(size.width / 2, size.height / 2)
                
                // Particle 1: Notification capsule
                val rad1 = Math.toRadians((orbitAngle).toDouble())
                val p1x = center.x + size.width * 0.38f * kotlin.math.cos(rad1).toFloat()
                val p1y = center.y + size.height * 0.12f * kotlin.math.sin(rad1).toFloat()
                
                rotate(degrees = 15f, pivot = center) {
                    val sizeP = 14.dp.toPx()
                    val heightP = 8.dp.toPx()
                    drawRoundRect(
                        color = Color(0xFF8FAEEA),
                        topLeft = Offset(p1x - sizeP / 2, p1y - heightP / 2),
                        size = Size(sizeP, heightP),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                    drawCircle(
                        color = Color(0xFF11182D),
                        radius = 1.5.dp.toPx(),
                        center = Offset(p1x, p1y)
                    )
                }

                // Particle 2: Application square
                val rad2 = Math.toRadians((orbitAngle + 120f).toDouble())
                val p2x = center.x + size.width * 0.2f * kotlin.math.cos(rad2).toFloat()
                val p2y = center.y + size.height * 0.4f * kotlin.math.sin(rad2).toFloat()
                
                rotate(degrees = -30f, pivot = center) {
                    val sizeP = 9.dp.toPx()
                    drawRect(
                        color = Color(0xFF667DFF),
                        topLeft = Offset(p2x - sizeP / 2, p2y - sizeP / 2),
                        size = Size(sizeP, sizeP)
                    )
                }

                // Particle 3: Activity pulsing circle
                val rad3 = Math.toRadians((orbitAngle + 240f).toDouble())
                val p3Radius = size.width * 0.3f
                val p3x = center.x + p3Radius * kotlin.math.cos(rad3).toFloat()
                val p3y = center.y + p3Radius * kotlin.math.sin(rad3).toFloat()
                
                drawCircle(
                    color = Color(0x336E82E9),
                    radius = 7.dp.toPx(),
                    center = Offset(p3x, p3y)
                )
                drawCircle(
                    color = Color(0xFF6E82E9),
                    radius = 3.dp.toPx(),
                    center = Offset(p3x, p3y)
                )
            }
        }
        
        Spacer(modifier = Modifier.weight(0.9f))
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Screen 4: When something feels off (headline → connected warning signals)
// ────────────────────────────────────────────────────────────────────────────
@Composable
private fun ColumnScope.ScreenFourSignals(pageOffset: Float) {
    val screenWidth = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    
    EntryAnimationContainer { entryProgress ->
        Spacer(modifier = Modifier.weight(0.12f))
        
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .onboardingTransition(entryProgress, pageOffset, isText = true)
        ) {
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(84.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x1811182D))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "When\nsomething\nfeels off.",
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 48.sp,
                letterSpacing = (-0.5).sp,
                color = OnboardingText,
                fontFamily = TrinetraFontFamily
            )
        }
        
        Spacer(modifier = Modifier.height(14.dp))
        
        Text(
            text = "Trinetra connects the dots\nbefore you have to.",
            fontSize = 16.sp,
            lineHeight = 24.sp,
            color = OnboardingTextSecondary,
            fontWeight = FontWeight.Normal,
            fontFamily = TrinetraFontFamily,
            modifier = Modifier
                .padding(start = 16.dp)
                .onboardingTransition(entryProgress, pageOffset, isText = true)
        )
        
        Spacer(modifier = Modifier.weight(1.0f))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center
        ) {
            // Layer 1: Ambient field (0.15x speed -> parallaxFactor = -0.85f)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, -0.85f)
            ) {
                val center = Offset(size.width / 2, size.height / 2)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x14667DFF), Color.Transparent),
                        center = center,
                        radius = size.width * 0.45f
                    ),
                    radius = size.width * 0.45f
                )
            }
            
            // Layer 3: Faint curved connecting lines (0.5x speed -> parallaxFactor = -0.5f)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, -0.5f)
            ) {
                val center = Offset(size.width / 2, size.height / 2)
                val node1Dest = Offset(size.width * 0.25f, size.height * 0.25f)
                val node2Dest = Offset(size.width * 0.75f, size.height * 0.25f)
                val node3Dest = Offset(size.width * 0.75f, size.height * 0.68f)
                
                val path1 = Path().apply {
                    moveTo(center.x, center.y)
                    cubicTo(
                        center.x - 60f, center.y - 10f,
                        node1Dest.x + 20f, node1Dest.y + 60f,
                        node1Dest.x, node1Dest.y
                    )
                }
                drawPath(
                    path = path1,
                    color = Color(0x337185E8),
                    style = Stroke(width = 2.dp.toPx())
                )
                
                val path2 = Path().apply {
                    moveTo(center.x, center.y)
                    cubicTo(
                        center.x + 60f, center.y - 10f,
                        node2Dest.x - 20f, node2Dest.y + 60f,
                        node2Dest.x, node2Dest.y
                    )
                }
                drawPath(
                    path = path2,
                    color = Color(0x22D99A35),
                    style = Stroke(width = 2.dp.toPx())
                )
                
                val path3 = Path().apply {
                    moveTo(center.x, center.y)
                    cubicTo(
                        center.x + 20f, center.y + 80f,
                        node3Dest.x - 60f, node3Dest.y - 10f,
                        node3Dest.x, node3Dest.y
                    )
                }
                drawPath(
                    path = path3,
                    color = Color(0x22D94D62),
                    style = Stroke(width = 2.dp.toPx())
                )
                
                // Translucent glowing circles behind nodes
                drawCircle(
                    color = Color(0x0C7185E8),
                    radius = 32.dp.toPx(),
                    center = node1Dest
                )
                drawCircle(
                    color = Color(0x0CD99A35),
                    radius = 32.dp.toPx(),
                    center = node2Dest
                )
                drawCircle(
                    color = Color(0x0CD94D62),
                    radius = 32.dp.toPx(),
                    center = node3Dest
                )
            }
            
            // Layer 4: The core VectorDrawable (0.8x speed -> parallaxFactor = -0.2f)
            FloatingContainer(
                durationMillis = 3000,
                maxTranslationY = 8f,
                maxTranslationX = 4f,
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .aspectRatio(1f)
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, -0.2f)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.trinetra_04_signals),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            // Layer 6: Floating particles overlay (1.0x speed -> parallaxFactor = 0f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, 0f)
            ) {
                FloatingParticlesOverlay(count = 4, seed = 3)
            }
        }
        
        Spacer(modifier = Modifier.weight(1.1f))
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Screen 5: Split headline (Quiet when safe / Visible when not)
// ────────────────────────────────────────────────────────────────────────────
@Composable
private fun ColumnScope.ScreenFiveAdaptive(pageOffset: Float) {
    val screenWidth = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    
    EntryAnimationContainer { entryProgress ->
        Spacer(modifier = Modifier.weight(0.12f))
        
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .onboardingTransition(entryProgress, pageOffset, isText = true)
        ) {
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(106.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x1811182D))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Quiet when\nyou're safe.",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Normal,
                    lineHeight = 38.sp,
                    letterSpacing = (-0.5).sp,
                    color = OnboardingTextSecondary,
                    fontFamily = TrinetraFontFamily
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Visible when\nyou're not.",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 44.sp,
                    letterSpacing = (-0.5).sp,
                    color = OnboardingText,
                    fontFamily = TrinetraFontFamily
                )
            }
        }
        
        Spacer(modifier = Modifier.height(14.dp))
        
        Text(
            text = "Protection adapts\nto the situation.",
            fontSize = 16.sp,
            lineHeight = 24.sp,
            color = OnboardingTextSecondary,
            fontWeight = FontWeight.Normal,
            fontFamily = TrinetraFontFamily,
            modifier = Modifier
                .padding(start = 16.dp)
                .onboardingTransition(entryProgress, pageOffset, isText = true)
        )
        
        Spacer(modifier = Modifier.weight(1.0f))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center
        ) {
            // Layer 1 & 2: Background fields (0.15x speed -> parallaxFactor = -0.85f)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, -0.85f)
            ) {
                val center = Offset(size.width / 2, size.height / 2)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x1E3AA968), Color.Transparent),
                        center = center,
                        radius = size.width * 0.4f
                    ),
                    radius = size.width * 0.4f
                )
                drawCircle(
                    color = Color(0x12667DFF),
                    radius = size.width * 0.35f,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            
            // Layer 3: Soft backing highlights (0.5x speed -> parallaxFactor = -0.5f)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, -0.5f)
            ) {
                val center = Offset(size.width / 2, size.height / 2)
                val safeCenter = Offset(center.x, center.y - 105f)
                val checkCenter = Offset(center.x, center.y + 45f)
                val protectCenter = Offset(center.x, center.y + 195f)

                drawCircle(
                    color = Color(0x1C3AA968),
                    radius = 38.dp.toPx(),
                    center = safeCenter
                )
                drawCircle(
                    color = Color(0x0AD99A35),
                    radius = 28.dp.toPx(),
                    center = checkCenter
                )
                drawCircle(
                    color = Color(0x0AD94D62),
                    radius = 28.dp.toPx(),
                    center = protectCenter
                )
            }
            
            // Layer 4: The VectorDrawable stack (0.8x speed -> parallaxFactor = -0.2f)
            FloatingContainer(
                durationMillis = 2900,
                maxTranslationY = 8f,
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .aspectRatio(1f)
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, -0.2f)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.trinetra_05_adaptive),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            // Layer 6: Floating particles overlay (1.0x speed -> parallaxFactor = 0f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, 0f)
            ) {
                FloatingParticlesOverlay(count = 3, seed = 4)
            }
        }
        
        Spacer(modifier = Modifier.weight(1.1f))
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Screen 6: Ready when you are (headline → stack cards → CTA)
// ────────────────────────────────────────────────────────────────────────────
@Composable
private fun ColumnScope.ScreenSixSetup(pageOffset: Float) {
    val screenWidth = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    
    EntryAnimationContainer { entryProgress ->
        Spacer(modifier = Modifier.weight(0.12f))
        
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .onboardingTransition(entryProgress, pageOffset, isText = true)
        ) {
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(84.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x1811182D))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Ready\nwhen you are.",
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 44.sp,
                letterSpacing = (-0.5).sp,
                color = OnboardingText,
                fontFamily = TrinetraFontFamily
            )
        }
        
        Spacer(modifier = Modifier.height(14.dp))
        
        Text(
            text = "Turn on the capabilities\nthat keep Trinetra\nlooking out for you.",
            fontSize = 16.sp,
            lineHeight = 24.sp,
            color = OnboardingTextSecondary,
            fontWeight = FontWeight.Normal,
            fontFamily = TrinetraFontFamily,
            modifier = Modifier
                .padding(start = 16.dp)
                .onboardingTransition(entryProgress, pageOffset, isText = true)
        )
        
        Spacer(modifier = Modifier.weight(0.8f))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "modules")
            val floatOffset1 by infiniteTransition.animateFloat(
                initialValue = -5f, targetValue = 5f,
                animationSpec = infiniteRepeatable(tween(2200, easing = EaseInOut), RepeatMode.Reverse),
                label = "offset1"
            )
            val floatOffset2 by infiniteTransition.animateFloat(
                initialValue = -6f, targetValue = 6f,
                animationSpec = infiniteRepeatable(tween(2600, easing = EaseInOut), RepeatMode.Reverse),
                label = "offset2"
            )

            // Layer 1: Ambient field (0.15x speed -> parallaxFactor = -0.85f)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, -0.85f)
            ) {
                val center = Offset(size.width / 2, size.height / 2)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x10667DFF), Color.Transparent),
                        center = center,
                        radius = size.width * 0.45f
                    ),
                    radius = size.width * 0.45f
                )
            }
            
            // Layer 3: Abstract floating modules (0.5x speed -> parallaxFactor = -0.5f)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, -0.5f)
            ) {
                val m1Center = Offset(size.width * 0.16f, size.height * 0.25f + floatOffset1.dp.toPx())
                val wavePath = Path().apply {
                    moveTo(m1Center.x - 12f, m1Center.y)
                    quadraticBezierTo(m1Center.x - 6f, m1Center.y - 12f, m1Center.x, m1Center.y)
                    quadraticBezierTo(m1Center.x + 6f, m1Center.y + 12f, m1Center.x + 12f, m1Center.y)
                }
                drawPath(wavePath, Color(0xFF667DFF), style = Stroke(width = 2.dp.toPx()))
                
                val m2Center = Offset(size.width * 0.84f, size.height * 0.2f + floatOffset2.dp.toPx())
                drawCircle(Color(0x228AA0FF), radius = 10.dp.toPx(), center = m2Center)
                drawCircle(Color(0xFF8AA0FF), radius = 4.dp.toPx(), center = m2Center)
                
                val m3Center = Offset(size.width * 0.12f, size.height * 0.72f + floatOffset2.dp.toPx())
                val diamondPath = Path().apply {
                    moveTo(m3Center.x, m3Center.y - 10f)
                    lineTo(m3Center.x + 10f, m3Center.y)
                    lineTo(m3Center.x, m3Center.y + 10f)
                    lineTo(m3Center.x - 10f, m3Center.y)
                    close()
                }
                drawPath(diamondPath, Color(0xFF6E82E9))
                
                val m4Center = Offset(size.width * 0.88f, size.height * 0.68f + floatOffset1.dp.toPx())
                val shieldPath = Path().apply {
                    moveTo(m4Center.x, m4Center.y - 12f)
                    lineTo(m4Center.x + 10f, m4Center.y - 6f)
                    lineTo(m4Center.x + 8f, m4Center.y + 8f)
                    quadraticBezierTo(m4Center.x, m4Center.y + 14f, m4Center.x, m4Center.y + 14f)
                    quadraticBezierTo(m4Center.x - 8f, m4Center.y + 8f, m4Center.x - 8f, m4Center.y + 8f)
                    lineTo(m4Center.x - 10f, m4Center.y - 6f)
                    close()
                }
                drawPath(shieldPath, Color(0xFF3AA968), style = Stroke(width = 1.8.dp.toPx()))
            }
            
            // Layer 4: The VectorDrawable card stack (0.8x speed -> parallaxFactor = -0.2f)
            FloatingContainer(
                durationMillis = 3100,
                maxTranslationY = 6f,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .aspectRatio(1f)
                    .onboardingTransition(entryProgress, pageOffset, isText = false, screenWidth, -0.2f)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.trinetra_06_setup),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        
        Spacer(modifier = Modifier.weight(1.4f))
    }
}
