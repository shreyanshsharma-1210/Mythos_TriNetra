package com.trustmesh.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.trustmesh.app.R

val TrinetraFontFamily = FontFamily(
    Font(R.font.google_sans_regular, FontWeight.Normal),
    Font(R.font.google_sans_medium, FontWeight.Medium),
    Font(R.font.google_sans_bold, FontWeight.Bold)
)

val Typography = Typography(
    // Large onboarding display text (e.g. Meet Trinetra)
    displayLarge = TextStyle(
        fontFamily = TrinetraFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 48.sp, // ~1.0x for tight editorial layout
        letterSpacing = (-1.0).sp
    ),
    displayMedium = TextStyle(
        fontFamily = TrinetraFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 40.sp, // ~1.0x
        letterSpacing = (-0.5).sp
    ),
    displaySmall = TextStyle(
        fontFamily = TrinetraFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp
    ),
    
    // Headline text
    headlineLarge = TextStyle(
        fontFamily = TrinetraFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.25).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = TrinetraFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    
    // Body Text
    bodyLarge = TextStyle(
        fontFamily = TrinetraFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 25.sp, // ~1.4x
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = TrinetraFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp, // ~1.4x
        letterSpacing = 0.15.sp
    ),
    
    // Small metadata / UI components
    labelLarge = TextStyle(
        fontFamily = TrinetraFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = TrinetraFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    
    // Legacy / other screen compatible styles
    titleLarge = TextStyle(
        fontFamily = TrinetraFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = TrinetraFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    )
)

