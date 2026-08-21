package com.trustmesh.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

import androidx.compose.material3.lightColorScheme

private val LightColorScheme = lightColorScheme(
    primary          = OnboardingAccentBlue,
    background       = OnboardingBackground,
    surface          = OnboardingBackground,
    onPrimary        = Color.White,
    onBackground     = OnboardingText,
    onSurface        = OnboardingText,
    surfaceVariant   = Color.White,
    onSurfaceVariant = OnboardingTextSecondary
)

@Composable
fun TrustMeshTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val context = view.context
            if (context is Activity) {
                val window = context.window
                // Enable edge-to-edge — onboarding will manage its own insets
                WindowCompat.setDecorFitsSystemWindows(window, false)
                // Transparent status bar so onboarding background bleeds through
                window.statusBarColor = Color.Transparent.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = true
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
