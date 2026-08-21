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

private val DarkColorScheme = darkColorScheme(
    primary          = SecurityAccent,
    background       = TrustMeshBackground,
    surface          = TrustMeshSurface,
    onPrimary        = TrustMeshBackground,
    onBackground     = TextPrimary,
    onSurface        = TextPrimary,
    surfaceVariant   = TrustMeshSurfaceElevated,
    onSurfaceVariant = TextSecondary
)

@Composable
fun TrustMeshTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
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
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
