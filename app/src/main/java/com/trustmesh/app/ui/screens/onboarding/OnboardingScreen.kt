@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.trustmesh.app.ui.screens.onboarding

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.trustmesh.app.ui.theme.OnboardingBackground
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.trustmesh.app.core.firewall.OverlayPermissionHelper
import com.trustmesh.app.firewall.RoleManagerHelper

/**
 * Root onboarding screen.
 *
 * Hosts all 6 pages with:
 *  • Edge-to-edge warm background
 *  • Premium HorizontalPager with interactive parallax transitions
 *  • Dynamic Top metadata + progress indicator
 *  • Animated Button Micro-interactions
 *  • Legible system status/navigation icons for light themes
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pages = onboardingPages
    var currentIndex by remember { mutableIntStateOf(0) }
    var showPermissionsPopup by remember { mutableStateOf(false) }

    // Configure system status bars dynamically for the light onboarding theme
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

    val pagerState = rememberPagerState(pageCount = { pages.size })

    // Sync button next / back navigation with pagerState
    LaunchedEffect(currentIndex) {
        if (pagerState.currentPage != currentIndex) {
            pagerState.animateScrollToPage(
                page = currentIndex,
                animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)
            )
        }
    }

    // Sync manual swipes with currentIndex
    LaunchedEffect(pagerState.currentPage) {
        currentIndex = pagerState.currentPage
    }

    // Back press goes to previous page
    BackHandler(enabled = currentIndex > 0) {
        currentIndex--
    }

    fun navigateNext() {
        if (currentIndex < pages.lastIndex) {
            currentIndex++
        } else {
            showPermissionsPopup = true
        }
    }

    fun navigateSkip() {
        showPermissionsPopup = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OnboardingBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top chrome: meta + progress ──────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 20.dp)
            ) {
                TrinetraTopMeta(
                    currentStep = currentIndex + 1,
                    totalSteps = pages.size,
                    onSkip = ::navigateSkip,
                    showSkip = currentIndex < pages.lastIndex
                )

                Spacer(modifier = Modifier.height(14.dp))

                TrinetraProgressIndicator(
                    currentStep = currentIndex + 1,
                    totalSteps = pages.size
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── Interactive HorizontalPager with Parallax ──────────────────
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                userScrollEnabled = true
            ) { pageIndex ->
                // Calculate pageOffset relative to viewport: pageIndex - scrollPosition
                val pageOffset = pageIndex - (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                
                OnboardingPage(
                    page = pages[pageIndex],
                    pageOffset = pageOffset,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── Bottom button ─────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                val currentPage = pages[currentIndex]
                TrinetraPrimaryButton(
                    label = currentPage.buttonLabel,
                    onClick = ::navigateNext,
                    isWide = currentPage.isLastPage
                )
            }
        }
        
        if (showPermissionsPopup) {
            PermissionsPopup(
                onDismiss = {
                    showPermissionsPopup = false
                    onFinish()
                },
                onAllGranted = {
                    showPermissionsPopup = false
                    onFinish()
                }
            )
        }
    }
}

@Composable
fun PermissionsPopup(
    onDismiss: () -> Unit,
    onAllGranted: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isCallScreeningActive by remember { mutableStateOf(RoleManagerHelper.isCallScreeningRoleGranted(context)) }
    var isOverlayGranted by remember { mutableStateOf(OverlayPermissionHelper.hasOverlayPermission(context)) }
    var isNotificationListenerGranted by remember { 
        mutableStateOf(
            android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")?.contains(context.packageName) == true
        )
    }
    
    val requiredPermissions = remember {
        val perms = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.PROCESS_OUTGOING_CALLS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        perms
    }
    
    var isStandardGranted by remember {
        mutableStateOf(requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }

    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isCallScreeningActive = RoleManagerHelper.isCallScreeningRoleGranted(context)
    }
    
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        isStandardGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isOverlayGranted = OverlayPermissionHelper.hasOverlayPermission(context)
                isCallScreeningActive = RoleManagerHelper.isCallScreeningRoleGranted(context)
                isNotificationListenerGranted = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")?.contains(context.packageName) == true
                isStandardGranted = requiredPermissions.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val allGranted = isCallScreeningActive && isOverlayGranted && isStandardGranted && isNotificationListenerGranted
    if (allGranted) {
        LaunchedEffect(Unit) { onAllGranted() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Required Permissions", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = androidx.compose.ui.graphics.Color.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("TriNetra needs a few permissions to protect you during incoming calls.", color = androidx.compose.ui.graphics.Color.DarkGray)
                
                Button(
                    onClick = { permissionLauncher.launch(requiredPermissions.toTypedArray()) },
                    enabled = !isStandardGranted,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isStandardGranted) "✓ Basic Permissions Granted" else "Grant Basic Permissions")
                }
                
                Button(
                    onClick = { 
                        val intent = RoleManagerHelper.getCallScreeningRoleIntent(context)
                        if (intent != null) roleLauncher.launch(intent)
                    },
                    enabled = !isCallScreeningActive,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isCallScreeningActive) "✓ Call Screening Granted" else "Grant Call Screening")
                }
                
                Button(
                    onClick = { OverlayPermissionHelper.requestOverlayPermission(context) },
                    enabled = !isOverlayGranted,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isOverlayGranted) "✓ Overlay Granted" else "Grant Overlay")
                }
                
                Button(
                    onClick = { 
                        try {
                            val intent = android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                            context.startActivity(intent)
                        } catch (e: Exception) {}
                    },
                    enabled = !isNotificationListenerGranted,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isNotificationListenerGranted) "✓ Notification Access Granted" else "Grant Notification Access")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (allGranted) onAllGranted() else onDismiss() }) {
                Text(if (allGranted) "Continue" else "Skip for now")
            }
        }
    )
}
