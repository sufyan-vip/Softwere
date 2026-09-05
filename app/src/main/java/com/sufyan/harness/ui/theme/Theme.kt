package com.sufyan.harness.ui.theme

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Centralised palette. Screens must never hardcode colours. */
object HarnessColors {
    val Base = Color(0xFF07080A)
    val Surface = Color(0xFF0D0F13)
    val SurfaceElevated = Color(0xFF14171D)
    val Border = Color(0xFF23272F)
    val TextPrimary = Color(0xFFE7EAF0)
    val TextSecondary = Color(0xFF9AA3B2)
    val TextMuted = Color(0xFF646C7A)
    val Accent = Color(0xFF5EEAD4)
    val AccentDim = Color(0xFF2A6F66)
    val Danger = Color(0xFFF87171)
    val Warn = Color(0xFFFBBF24)
    val Ok = Color(0xFF4ADE80)
    val Info = Color(0xFF60A5FA)

    val LightBase = Color(0xFFF7F8FA)
    val LightSurface = Color(0xFFFFFFFF)
    val LightBorder = Color(0xFFE2E5EA)
    val LightText = Color(0xFF14171D)
}

/** Spacing scale — 4dp base grid. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Corner radius scale. */
object Radius {
    val sm = 6.dp
    val md = 10.dp
    val lg = 14.dp
    val pill = 999.dp
}

private val DarkScheme = darkColorScheme(
    primary = HarnessColors.Accent,
    onPrimary = Color(0xFF04211E),
    secondary = HarnessColors.Info,
    background = HarnessColors.Base,
    onBackground = HarnessColors.TextPrimary,
    surface = HarnessColors.Surface,
    onSurface = HarnessColors.TextPrimary,
    surfaceVariant = HarnessColors.SurfaceElevated,
    onSurfaceVariant = HarnessColors.TextSecondary,
    outline = HarnessColors.Border,
    error = HarnessColors.Danger,
)

private val LightScheme = lightColorScheme(
    primary = HarnessColors.AccentDim,
    onPrimary = Color.White,
    background = HarnessColors.LightBase,
    onBackground = HarnessColors.LightText,
    surface = HarnessColors.LightSurface,
    onSurface = HarnessColors.LightText,
    surfaceVariant = Color(0xFFEDEFF3),
    onSurfaceVariant = Color(0xFF4B5462),
    outline = HarnessColors.LightBorder,
    error = Color(0xFFB3261E),
)

val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
)

private val HarnessTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

enum class ThemeMode { Dark, Light, System }

@Composable
fun SufyanHarnessTheme(mode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = HarnessTypography,
        content = content,
    )
}
