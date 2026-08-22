package com.trustmesh.app.ui.screens.home

import android.app.Activity
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.core.incident.IncidentStatus
import com.trustmesh.app.core.incident.SecurityIncidentManager
import com.trustmesh.app.interaction.InteractionManager
import com.trustmesh.app.ui.theme.*

enum class DashboardState {
    CLEAR, CHECK, PROTECT
}

enum class RowStatus {
    NORMAL, CAUTION, WARNING
}

data class ActivityItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val status: RowStatus,
    val isReal: Boolean
)

@Composable
fun HomeScreen(
    onInteractionClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onSecurityInsightsClick: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf("home") }
    var isAskTrinetraOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                InteractionManager.loadRealCallLogs(context)
                InteractionManager.loadRealContacts(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Configure status/navigation bars for edge-to-edge light layout
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
            }
        }
    }

    // Bind state to real active incident from SecurityIncidentManager
    val activeIncident by SecurityIncidentManager.activeIncident.collectAsState()
    val dashboardState = remember(activeIncident) {
        val incident = activeIncident
        if (incident != null && incident.status == IncidentStatus.ACTIVE) {
            when (incident.severity) {
                RiskLevel.HIGH, RiskLevel.CRITICAL -> DashboardState.PROTECT
                RiskLevel.ELEVATED -> DashboardState.CHECK
                else -> DashboardState.CLEAR
            }
        } else {
            DashboardState.CLEAR
        }
    }

    // Manual test state toggle (cycles on clicking active pill or illustration)
    var manualStateOverride by remember { mutableStateOf<DashboardState?>(null) }
    val effectiveState = manualStateOverride ?: dashboardState

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OnboardingBackground)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header bar
            DashboardHeader(
                unreadCount = if (effectiveState != DashboardState.CLEAR) 1 else 0,
                onProfileClick = onSettingsClick
            )

            // Switch content based on selected bottom tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (selectedTab) {
                    "home" -> {
                        DashboardContent(
                            state = effectiveState,
                            onInteractionClick = onInteractionClick,
                            onStateCycle = {
                                manualStateOverride = when (manualStateOverride) {
                                    null, DashboardState.CLEAR -> DashboardState.CHECK
                                    DashboardState.CHECK -> DashboardState.PROTECT
                                    DashboardState.PROTECT -> DashboardState.CLEAR
                                }
                            }
                        )
                    }
                    "calls" -> {
                        CallsTabContent(onInteractionClick = onInteractionClick)
                    }
                    "contacts" -> {
                        ContactsTabContent()
                    }
                }
            }

            // Safe height offset for floating bottom navigation
            Spacer(modifier = Modifier.height(100.dp))
        }

        // Floating Bottom Navigation Bar (rests above safe navigation region)
        FloatingBottomNavigation(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            onCenterClick = { isAskTrinetraOpen = true },
            onInsightsClick = onSecurityInsightsClick,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )

        // Overlay Slide-up assistant panel (Ask Trinetra)
        AskTrinetraPanel(
            isOpen = isAskTrinetraOpen,
            onClose = { isAskTrinetraOpen = false }
        )
    }
}

@Composable
fun DashboardHeader(
    unreadCount: Int,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Good evening,",
                style = TextStyle(
                    fontFamily = TrinetraFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 16.sp,
                    color = OnboardingTextSecondary
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Aditya",
                style = TextStyle(
                    fontFamily = TrinetraFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = OnboardingText
                )
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Notification button
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.White, CircleShape)
                    .border(1.dp, OnboardingDivider, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Notifications,
                    contentDescription = "Notifications",
                    tint = OnboardingText,
                    modifier = Modifier.size(20.dp)
                )
                if (unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .align(Alignment.TopEnd)
                            .offset(x = (-4).dp, y = 4.dp)
                            .background(OnboardingAccentRed, CircleShape)
                    )
                }
            }

            // Profile Avatar
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(OnboardingAccentBlue, OnboardingAccentPurple)
                        )
                    )
                    .clickable { onProfileClick() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "A",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }
    }
}

@Composable
fun DashboardContent(
    state: DashboardState,
    onInteractionClick: (String) -> Unit,
    onStateCycle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val realInteractions by InteractionManager.interactions.collectAsState()

    // Counts setup
    val callsCount = remember(realInteractions) {
        if (realInteractions.isNotEmpty()) realInteractions.size else 3
    }
    val threatsCount = remember(realInteractions, state) {
        if (realInteractions.isNotEmpty()) {
            realInteractions.count { it.riskLevel == RiskLevel.HIGH || it.riskLevel == RiskLevel.CRITICAL }
        } else {
            when (state) {
                DashboardState.CLEAR -> 0
                DashboardState.CHECK -> 1
                DashboardState.PROTECT -> 2
            }
        }
    }

    // Activities list
    val displayActivities = remember(realInteractions, state) {
        if (realInteractions.isNotEmpty()) {
            realInteractions.take(3).map { interaction ->
                val status = when (interaction.riskLevel) {
                    RiskLevel.LOW -> RowStatus.NORMAL
                    RiskLevel.ELEVATED -> RowStatus.CAUTION
                    else -> RowStatus.WARNING
                }
                ActivityItem(
                    id = interaction.id,
                    title = interaction.title,
                    subtitle = "${interaction.appName ?: "Call"} · ${interaction.timestamp}",
                    status = status,
                    isReal = true
                )
            }
        } else {
            listOf(
                ActivityItem(
                    id = "mock_1",
                    title = "Rahul Sharma",
                    subtitle = "Incoming call · 11:45 AM",
                    status = if (state == DashboardState.CLEAR) RowStatus.NORMAL else RowStatus.WARNING,
                    isReal = false
                ),
                ActivityItem(
                    id = "mock_2",
                    title = "WhatsApp notification",
                    subtitle = "money transfer · 10:32 AM",
                    status = RowStatus.NORMAL,
                    isReal = false
                ),
                ActivityItem(
                    id = "mock_3",
                    title = "Unknown number",
                    subtitle = "Missed call · Yesterday",
                    status = RowStatus.CAUTION,
                    isReal = false
                )
            )
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // Hero text & description
        item {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                val headline = when (state) {
                    DashboardState.CLEAR -> "You're\nprotected."
                    DashboardState.CHECK -> "Something needs\na closer look."
                    DashboardState.PROTECT -> "Something\nfeels wrong."
                }
                val supporting = when (state) {
                    DashboardState.CLEAR -> "Nothing unusual detected today."
                    DashboardState.CHECK -> "Trinetra noticed an unusual pattern."
                    DashboardState.PROTECT -> "Trinetra detected signals worth your attention."
                }

                Text(
                    text = headline,
                    style = TextStyle(
                        fontFamily = TrinetraFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 42.sp,
                        lineHeight = 44.sp,
                        color = OnboardingText
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = supporting,
                    style = TextStyle(
                        fontFamily = TrinetraFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 18.sp,
                        color = OnboardingTextSecondary
                    )
                )
            }
        }

        // Animated orbital illustration representing Trinetra
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onStateCycle // Cycle dashboard state on click
                    ),
                contentAlignment = Alignment.Center
            ) {
                TrinetraOrbIllustration(state = state)
            }
        }

        // System active status pill
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                ActiveStatusPill(state = state, onClick = onStateCycle)
            }
        }

        // Supporting Statistics
        item {
            QuickStatsSection(
                callsCount = callsCount,
                threatsCount = threatsCount,
                state = state
            )
        }

        // Recent Activity header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent activity",
                    style = TextStyle(
                        fontFamily = TrinetraFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = OnboardingText
                    )
                )
                Text(
                    text = "View all",
                    style = TextStyle(
                        fontFamily = TrinetraFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = OnboardingAccentBlue
                    ),
                    modifier = Modifier.clickable {
                        // Action could switch tabs
                    }
                )
            }
        }

        // Recent Activity list rows
        items(displayActivities, key = { it.id }) { activity ->
            ActivityRow(
                title = activity.title,
                subtitle = activity.subtitle,
                icon = {
                    val iconVector = if (activity.title.contains("WhatsApp", ignoreCase = true)) {
                        Icons.Rounded.Notifications
                    } else {
                        Icons.Rounded.Call
                    }
                    Icon(
                        imageVector = iconVector,
                        contentDescription = null,
                        tint = OnboardingTextSecondary.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                },
                status = activity.status,
                onClick = {
                    if (activity.isReal) {
                        onInteractionClick(activity.id)
                    }
                }
            )
            HorizontalDivider(color = OnboardingDivider.copy(alpha = 0.5f))
        }
    }
}

@Composable
fun TrinetraOrbIllustration(
    state: DashboardState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_rotation")
    
    val rotationAngle1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring_rotate_1"
    )

    val rotationAngle2 by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring_rotate_2"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_pulse"
    )

    val floatOffset1 by infiniteTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "particle_float_1"
    )

    val floatOffset2 by infiniteTransition.animateFloat(
        initialValue = 4f,
        targetValue = -4f,
        animationSpec = infiniteRepeatable(
            animation = tween(5000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "particle_float_2"
    )

    val stateColor = when (state) {
        DashboardState.CLEAR -> OnboardingAccentGreen
        DashboardState.CHECK -> OnboardingAccentAmber
        DashboardState.PROTECT -> OnboardingAccentRed
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.height * 0.32f

            // 1. Soft radial background glow representing the field
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        stateColor.copy(alpha = 0.22f),
                        stateColor.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 2.2f
                ),
                radius = baseRadius * 2.2f,
                center = center
            )

            // 2. Rotating orbital rings
            rotate(rotationAngle1, center) {
                drawOval(
                    color = OnboardingText.copy(alpha = 0.07f),
                    topLeft = Offset(center.x - baseRadius * 1.5f, center.y - baseRadius * 0.6f),
                    size = Size(baseRadius * 3f, baseRadius * 1.2f),
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            rotate(rotationAngle2, center) {
                drawOval(
                    color = OnboardingText.copy(alpha = 0.05f),
                    topLeft = Offset(center.x - baseRadius * 1.2f, center.y - baseRadius * 0.8f),
                    size = Size(baseRadius * 2.4f, baseRadius * 1.6f),
                    style = Stroke(width = 1.dp.toPx())
                )
                
                // Dash circle for clear/check state
                if (state != DashboardState.PROTECT) {
                    drawCircle(
                        color = stateColor.copy(alpha = 0.12f),
                        radius = baseRadius * 1.1f,
                        center = center,
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 16f), 0f)
                        )
                    )
                }
            }

            // 3. Floating particles (rendered relative to animated offsets)
            drawCircle(
                color = stateColor.copy(alpha = 0.6f),
                radius = 5.dp.toPx(),
                center = Offset(center.x + baseRadius * 0.8f + floatOffset1, center.y - baseRadius * 0.7f + floatOffset2)
            )

            drawCircle(
                color = OnboardingText.copy(alpha = 0.4f),
                radius = 3.dp.toPx(),
                center = Offset(center.x - baseRadius * 1.1f + floatOffset2, center.y + baseRadius * 0.4f + floatOffset1)
            )

            drawCircle(
                color = stateColor.copy(alpha = 0.3f),
                radius = 4.dp.toPx(),
                center = Offset(center.x - baseRadius * 0.6f + floatOffset1, center.y - baseRadius * 0.9f + floatOffset2)
            )

            // 4. Central Guardian Orb (pulses slightly)
            scale(pulseScale, center) {
                // outer shadow ring
                drawCircle(
                    color = OnboardingText.copy(alpha = 0.12f),
                    radius = (baseRadius * 0.5f) + 4.dp.toPx(),
                    center = center
                )
                // solid navy core
                drawCircle(
                    color = OnboardingText,
                    radius = baseRadius * 0.5f,
                    center = center
                )
                // colored active boundary ring
                drawCircle(
                    color = stateColor,
                    radius = baseRadius * 0.5f,
                    center = center,
                    style = Stroke(width = 3.dp.toPx())
                )
                // glassy ambient highlight
                drawCircle(
                    color = Color.White.copy(alpha = 0.22f),
                    radius = baseRadius * 0.12f,
                    center = Offset(center.x - baseRadius * 0.15f, center.y - baseRadius * 0.15f)
                )
            }
        }
    }
}

@Composable
fun ActiveStatusPill(
    state: DashboardState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulsing_dot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )

    val (bg, txt, label) = when (state) {
        DashboardState.CLEAR -> Triple(Color(0xFFEEF8F0), Color(0xFF2E7D32), "Trinetra is active")
        DashboardState.CHECK -> Triple(Color(0xFFFFF8E1), Color(0xFFF57F17), "System caution")
        DashboardState.PROTECT -> Triple(Color(0xFFFFEBEE), Color(0xFFC62828), "Attention needed")
    }

    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(bg)
            .border(1.dp, txt.copy(alpha = 0.15f), RoundedCornerShape(19.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Pulse dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = dotAlpha }
                    .background(txt, CircleShape)
            )

            Text(
                text = label,
                style = TextStyle(
                    fontFamily = TrinetraFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = txt
                )
            )
        }
    }
}

@Composable
fun QuickStatsSection(
    callsCount: Int,
    threatsCount: Int,
    state: DashboardState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Calls Analyzed
        Card(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, OnboardingDivider),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = callsCount.toString(),
                        style = TextStyle(
                            fontFamily = TrinetraFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp,
                            color = OnboardingText
                        )
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(OnboardingBackground, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Call,
                            contentDescription = null,
                            tint = OnboardingText.copy(alpha = 0.6f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Calls analyzed\ntoday",
                    style = TextStyle(
                        fontFamily = TrinetraFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        color = OnboardingTextSecondary
                    )
                )
            }
        }

        // Threats detected
        val threatColor = when (state) {
            DashboardState.CLEAR -> OnboardingAccentGreen
            DashboardState.CHECK -> OnboardingAccentAmber
            DashboardState.PROTECT -> OnboardingAccentRed
        }
        Card(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, OnboardingDivider),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = threatsCount.toString(),
                        style = TextStyle(
                            fontFamily = TrinetraFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp,
                            color = if (threatsCount > 0) threatColor else OnboardingText
                        )
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(threatColor.copy(alpha = 0.1f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = threatColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (threatsCount == 1) "Threat detected\ntoday" else "Threats detected\ntoday",
                    style = TextStyle(
                        fontFamily = TrinetraFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        color = OnboardingTextSecondary
                    )
                )
            }
        }
    }
}

@Composable
fun ActivityRow(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    status: RowStatus,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(OnboardingBackground, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = TextStyle(
                    fontFamily = TrinetraFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = OnboardingText
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = TextStyle(
                    fontFamily = TrinetraFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 13.sp,
                    color = OnboardingTextSecondary
                )
            )
        }

        when (status) {
            RowStatus.NORMAL -> {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(OnboardingAccentGreen.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", color = OnboardingAccentGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            RowStatus.CAUTION -> {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(OnboardingAccentAmber.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("!", color = OnboardingAccentAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            RowStatus.WARNING -> {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(OnboardingAccentRed.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("!", color = OnboardingAccentRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

// ── Floating Bottom Navigation ──────────────────────────────────────────────

@Composable
fun FloatingBottomNavigation(
    selectedTab: String,
    onTabSelected: (String) -> Unit,
    onCenterClick: () -> Unit,
    onInsightsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            shape = RoundedCornerShape(30.dp),
            color = Color.White.copy(alpha = 0.95f),
            border = BorderStroke(1.dp, OnboardingDivider),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavItem(
                    icon = Icons.Rounded.Home,
                    isSelected = selectedTab == "home",
                    onClick = { onTabSelected("home") }
                )
                
                NavItem(
                    icon = Icons.Rounded.Call,
                    isSelected = selectedTab == "calls",
                    onClick = { onTabSelected("calls") }
                )

                Spacer(modifier = Modifier.width(64.dp))

                // Security Insights icon — tapping launches the full VCD module
                NavItem(
                    icon = Icons.Rounded.Info,
                    isSelected = false, // VCD is a separate nav destination, not an in-page tab
                    onClick = onInsightsClick
                )

                NavItem(
                    icon = Icons.Rounded.Person,
                    isSelected = selectedTab == "contacts",
                    onClick = { onTabSelected("contacts") }
                )
            }
        }

        CenterOrbButton(
            onClick = onCenterClick,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-14).dp)
        )
    }
}

@Composable
fun RowScope.NavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val duration = 250
    val animatedBgColor by animateColorAsState(
        targetValue = if (isSelected) OnboardingText else Color.Transparent,
        animationSpec = tween(duration),
        label = "nav_item_bg"
    )
    val animatedIconColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else OnboardingTextSecondary.copy(alpha = 0.6f),
        animationSpec = tween(duration),
        label = "nav_item_icon"
    )

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(
                onClick = onClick,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(animatedBgColor, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = animatedIconColor,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
fun CenterOrbButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "center_button_scale"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "center_orb_anim")
    val orbitRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "center_orb_rotate"
    )

    Box(
        modifier = modifier
            .size(58.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(6.dp, CircleShape, clip = false)
            .background(OnboardingText, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.width * 0.45f
            
            rotate(orbitRotation, center) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.25f),
                    radius = baseRadius * 0.72f,
                    center = center,
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f), 0f)
                    )
                )
                drawCircle(
                    color = Color.White,
                    radius = 2.dp.toPx(),
                    center = Offset(center.x + baseRadius * 0.72f, center.y)
                )
            }

            drawCircle(
                color = Color.White,
                radius = 4.dp.toPx(),
                center = center
            )
            
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.4f), Color.Transparent),
                    center = center,
                    radius = baseRadius * 0.4f
                ),
                radius = baseRadius * 0.4f,
                center = center
            )
        }
    }
}

// ── Overlay Intelligence Drawer (Ask Trinetra) ──────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskTrinetraPanel(
    isOpen: Boolean,
    onClose: () -> Unit
) {
    AnimatedVisibility(
        visible = isOpen,
        enter = fadeIn(tween(300)) + slideInVertically(tween(350, easing = EaseOutQuad)) { it },
        exit = fadeOut(tween(250)) + slideOutVertically(tween(300, easing = EaseInQuad)) { it },
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.55f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {} // block click propagation
                    ),
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                color = OnboardingBackground,
                border = BorderStroke(1.dp, OnboardingDivider)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .background(OnboardingTextSecondary.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    MiniSpinningOrb(modifier = Modifier.size(64.dp))
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "How can I help you, Aditya?",
                        style = TextStyle(
                            fontFamily = TrinetraFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = OnboardingText
                        )
                    )
                    
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    Text(
                        text = "Ask Trinetra to check background services, call integrity, or suspicious alerts.",
                        style = TextStyle(
                            fontFamily = TrinetraFontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = OnboardingTextSecondary
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PromptPill("Verify last incoming call")
                        PromptPill("Run system security scan")
                        PromptPill("Check app permissions")
                    }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        placeholder = { 
                            Text(
                                "Ask Trinetra anything...", 
                                style = TextStyle(fontFamily = TrinetraFontFamily, fontSize = 14.sp)
                            ) 
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedBorderColor = OnboardingText.copy(alpha = 0.4f),
                            unfocusedBorderColor = OnboardingDivider
                        ),
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = null,
                                tint = OnboardingText
                            )
                        },
                        readOnly = true
                    )
                }
            }
        }
    }
}

@Composable
fun MiniSpinningOrb(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "mini_orb_anim")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing)
        ),
        label = "rotation"
    )
    
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.width / 2f
        
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(OnboardingText.copy(alpha = 0.15f), Color.Transparent),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
        
        rotate(rotation, center) {
            drawCircle(
                color = OnboardingText.copy(alpha = 0.2f),
                radius = radius * 0.6f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
            drawCircle(
                color = OnboardingAccentGreen,
                radius = 3.dp.toPx(),
                center = Offset(center.x + radius * 0.6f, center.y)
            )
        }
        
        drawCircle(
            color = OnboardingText,
            radius = radius * 0.3f,
            center = center
        )
    }
}

@Composable
fun PromptPill(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .border(1.dp, OnboardingDivider, RoundedCornerShape(16.dp))
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = TrinetraFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = OnboardingText
            )
        )
    }
}

// ── Secondary Tab Contents (Light Editorial Styling) ───────────────────────

@Composable
fun CallsTabContent(
    onInteractionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val realInteractions by InteractionManager.interactions.collectAsState()
    var selectedFilter by remember { mutableStateOf("Calls") }

    val filteredInteractions = remember(realInteractions, selectedFilter) {
        realInteractions.filter { interaction ->
            val isCall = interaction.appName?.equals("Phone", ignoreCase = true) == true ||
                    interaction.summary.contains("call", ignoreCase = true) ||
                    interaction.evidence.any { it.contains("call", ignoreCase = true) }
            
            if (selectedFilter == "Calls") isCall else !isCall
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
    ) {
        item {
            Text(
                text = "Activity history",
                style = TextStyle(
                    fontFamily = TrinetraFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    color = OnboardingText
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Trinetra performs real-time verification of incoming communication.",
                style = TextStyle(
                    fontFamily = TrinetraFontFamily,
                    fontSize = 15.sp,
                    color = OnboardingTextSecondary
                )
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Segmented Control
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(OnboardingBackground, RoundedCornerShape(24.dp))
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("Calls", "Messaging").forEach { filter ->
                    val isSelected = selectedFilter == filter
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) Color.White else Color.Transparent)
                            .clickable { selectedFilter = filter },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = filter,
                            style = TextStyle(
                                fontFamily = TrinetraFontFamily,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 15.sp,
                                color = if (isSelected) OnboardingText else OnboardingTextSecondary
                            )
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (filteredInteractions.isEmpty()) {
            item {
                Text(
                    text = if (selectedFilter == "Calls") "No real calls recorded yet." else "No messaging activity recorded yet.",
                    style = TextStyle(
                        fontFamily = TrinetraFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = OnboardingAccentBlue
                    ),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        } else {
            items(filteredInteractions) { interaction ->
                val status = when (interaction.riskLevel) {
                    RiskLevel.LOW -> RowStatus.NORMAL
                    RiskLevel.ELEVATED -> RowStatus.CAUTION
                    else -> RowStatus.WARNING
                }
                ActivityRow(
                    title = interaction.title,
                    subtitle = "${interaction.appName ?: if (selectedFilter == "Calls") "Call" else "Message"} · ${interaction.timestamp}",
                    icon = {
                        val iconVector = if (selectedFilter == "Messaging" || interaction.title.contains("WhatsApp", ignoreCase = true)) {
                            Icons.Rounded.Notifications
                        } else {
                            Icons.Rounded.Call
                        }
                        Icon(
                            imageVector = iconVector,
                            contentDescription = null,
                            tint = OnboardingTextSecondary.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    status = status,
                    onClick = { onInteractionClick(interaction.id) }
                )
                HorizontalDivider(color = OnboardingDivider.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
fun InsightsTabContent(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
    ) {
        item {
            Text(
                text = "Security Insights",
                style = TextStyle(
                    fontFamily = TrinetraFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    color = OnboardingText
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Understanding signals and scanning for active anomalies.",
                style = TextStyle(
                    fontFamily = TrinetraFontFamily,
                    fontSize = 15.sp,
                    color = OnboardingTextSecondary
                )
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        items(
            listOf(
                Triple("Device Integrity", "Verified 12 components. All security layers are fully operating.", OnboardingAccentGreen),
                Triple("Communication Intelligence", "Trinetra processed 3 interactions and found no warning indicators.", OnboardingAccentBlue),
                Triple("Network Safe Routing", "Wi-Fi connections are secure. Active monitoring is shielding data.", OnboardingAccentGreen)
            )
        ) { insight ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, OnboardingDivider)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = insight.first,
                            style = TextStyle(
                                fontFamily = TrinetraFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = OnboardingText
                            )
                        )
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(insight.third.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = insight.third,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = insight.second,
                        style = TextStyle(
                            fontFamily = TrinetraFontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = OnboardingTextSecondary
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun ContactsTabContent(modifier: Modifier = Modifier) {
    val realContacts by InteractionManager.contacts.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
    ) {
        item {
            Text(
                text = "Trusted Network",
                style = TextStyle(
                    fontFamily = TrinetraFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    color = OnboardingText
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "People in your contacts are automatically verified.",
                style = TextStyle(
                    fontFamily = TrinetraFontFamily,
                    fontSize = 15.sp,
                    color = OnboardingTextSecondary
                )
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        val displayContacts = if (realContacts.isNotEmpty()) {
            realContacts
        } else {
            listOf(
                Pair("Rahul Sharma", "Safe contact · Last verified today"),
                Pair("Priya Patel", "Safe contact · Last verified 2 days ago"),
                Pair("Amit Singh", "Safe contact · Last verified 5 days ago")
            )
        }

        items(displayContacts) { contact ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(OnboardingAccentBlue.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = contact.first.take(1),
                        color = OnboardingAccentBlue,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = contact.first,
                        style = TextStyle(
                            fontFamily = TrinetraFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = OnboardingText
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = contact.second,
                        style = TextStyle(
                            fontFamily = TrinetraFontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 13.sp,
                            color = OnboardingTextSecondary
                        )
                    )
                }
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(OnboardingAccentGreen.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", color = OnboardingAccentGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            HorizontalDivider(color = OnboardingDivider.copy(alpha = 0.5f))
        }
    }
}
