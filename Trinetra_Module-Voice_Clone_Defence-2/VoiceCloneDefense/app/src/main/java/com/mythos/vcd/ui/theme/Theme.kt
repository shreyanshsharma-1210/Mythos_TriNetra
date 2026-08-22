package com.mythos.vcd.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mythos.vcd.pipeline.Level

private val Ink = Color(0xFF0B1220)
private val InkElevated = Color(0xFF141C2E)
private val Paper = Color(0xFFF7F8FB)
private val Accent = Color(0xFF3B82F6)

/**
 * Status colours are defined once, here, so SAFE never renders in two different greens across
 * screens. On a fraud-warning surface, a colour the user has learned to read as "fine" appearing
 * on a screen that means something else is a real failure mode, not a polish issue.
 */
object StatusColors {
    val safe = Color(0xFF16A34A)
    val safeContainer = Color(0xFFDCFCE7)
    val suspicious = Color(0xFFD97706)
    val suspiciousContainer = Color(0xFFFEF3C7)
    val critical = Color(0xFFDC2626)
    val criticalContainer = Color(0xFFFEE2E2)
    val indeterminate = Color(0xFF64748B)
    val indeterminateContainer = Color(0xFFE2E8F0)

    fun accent(level: Level): Color = when (level) {
        Level.SAFE -> safe
        Level.SUSPICIOUS -> suspicious
        Level.CRITICAL -> critical
        Level.INDETERMINATE -> indeterminate
    }

    fun container(level: Level): Color = when (level) {
        Level.SAFE -> safeContainer
        Level.SUSPICIOUS -> suspiciousContainer
        Level.CRITICAL -> criticalContainer
        Level.INDETERMINATE -> indeterminateContainer
    }

    fun label(level: Level): String = when (level) {
        Level.SAFE -> "SAFE"
        Level.SUSPICIOUS -> "SUSPICIOUS"
        Level.CRITICAL -> "CRITICAL"
        Level.INDETERMINATE -> "NOT MEASURED"
    }

    /**
     * The badge wording on a call screen.
     *
     * Deliberately different from [label]. "SAFE" as a caller-ID badge overclaims — it reads as a
     * guarantee about the person, when what was actually checked is that the voice matches an
     * enrolled print and shows no synthesis signature. These say what was measured.
     */
    fun badge(level: Level): String = when (level) {
        Level.SAFE -> "Voice verified"
        Level.SUSPICIOUS -> "Voice not confirmed"
        Level.CRITICAL -> "Possible cloned voice"
        Level.INDETERMINATE -> "Checking voice…"
    }
}

/** Brand palette for the calling surfaces. */
object CallColors {
    val brand = Color(0xFF0B63CE)
    val brandDark = Color(0xFF063E86)
    val onBrand = Color(0xFFFFFFFF)
    val brandMuted = Color(0xFFE8F1FD)
}

private val DarkColors = darkColorScheme(
    primary = Accent,
    background = Ink,
    surface = InkElevated,
    onBackground = Color(0xFFE8EAF0),
    onSurface = Color(0xFFE8EAF0),
)

private val LightColors = lightColorScheme(
    primary = Accent,
    background = Paper,
    surface = Color.White,
    onBackground = Ink,
    onSurface = Ink,
)

private val VcdTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 48.sp,
        letterSpacing = (-1).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.6.sp,
    ),
)

@Composable
fun VcdTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = VcdTypography,
        content = content,
    )
}
