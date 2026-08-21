package com.trustmesh.app.ui.screens.onboarding

import androidx.annotation.DrawableRes
import com.trustmesh.app.R

/**
 * Immutable data model for a single onboarding page.
 */
data class OnboardingPageData(
    val step: Int,                       // 1-indexed
    val totalSteps: Int = 6,
    val title: String,                   // Multi-line headline (use \n for line breaks)
    val description: String,
    @DrawableRes val illustrationRes: Int,
    val illustrationAspectRatio: Float = 1f,  // width / height of the illustration
    val buttonLabel: String = "Next",
    val isLastPage: Boolean = false
)

val onboardingPages = listOf(
    OnboardingPageData(
        step = 1,
        title = "Meet\nTrinetra.",
        description = "Security intelligence for\neveryday interactions.",
        illustrationRes = R.drawable.trinetra_01_meet,
        illustrationAspectRatio = 1f
    ),
    OnboardingPageData(
        step = 2,
        title = "Your\nconversations\nstay yours.",
        description = "Trinetra doesn't record\nor analyze call audio.",
        illustrationRes = R.drawable.trinetra_02_no_audio,
        illustrationAspectRatio = 1f
    ),
    OnboardingPageData(
        step = 3,
        title = "It notices\nthe little\nthings.",
        description = "Notifications, apps and\nactivity help us understand\ncontext.",
        illustrationRes = R.drawable.trinetra_03_context,
        illustrationAspectRatio = 1f
    ),
    OnboardingPageData(
        step = 4,
        title = "When\nsomething\nfeels off.",
        description = "Trinetra connects the dots\nbefore you have to.",
        illustrationRes = R.drawable.trinetra_04_signals,
        illustrationAspectRatio = 1f
    ),
    OnboardingPageData(
        step = 5,
        title = "Quiet when\nyou're safe.\n\nVisible when\nyou're not.",
        description = "Protection adapts\nto the situation.",
        illustrationRes = R.drawable.trinetra_05_adaptive,
        illustrationAspectRatio = 1f
    ),
    OnboardingPageData(
        step = 6,
        title = "Ready\nwhen you are.",
        description = "Turn on the capabilities\nthat keep Trinetra\nlooking out for you.",
        illustrationRes = R.drawable.trinetra_06_setup,
        illustrationAspectRatio = 1f,
        buttonLabel = "Set up Trinetra",
        isLastPage = true
    )
)
