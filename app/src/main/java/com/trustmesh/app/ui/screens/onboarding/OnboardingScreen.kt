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
            onFinish()
        }
    }

    fun navigateSkip() {
        onFinish()
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
    }
}
