package com.dafeiyu.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5F1FB),
    onPrimaryContainer = Color(0xFF004A8A),
    secondary = Color(0xFF566778),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9EDF2),
    onSecondaryContainer = Color(0xFF283746),
    tertiary = Color(0xFF427566),
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEBEBEB),
    onSurfaceVariant = Color(0xFF616161),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9F9F9),
    surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFE8E8E8),
    surfaceContainerHighest = Color(0xFFE1E1E1),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFFE5E5E5),
    error = Color(0xFFBB3D36),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF64B5FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF153E62),
    onPrimaryContainer = Color(0xFFD6EAFF),
    secondary = Color(0xFFB5C4D4),
    onSecondary = Color(0xFF263440),
    secondaryContainer = Color(0xFF303D49),
    onSecondaryContainer = Color(0xFFDFEAF5),
    tertiary = Color(0xFF9CCFBA),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF353535),
    onSurfaceVariant = Color(0xFFC7C7C7),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),
    outline = Color(0xFF858585),
    outlineVariant = Color(0xFF252525),
    error = Color(0xFFFFB4AA),
    onError = Color(0xFF690005),
)

/** Persisted font-family key (DemoRepository.fontFamily) shared by the theme and the font settings page. */
data class AppFontFamilyOption(
    val key: String,
    val title: String,
    val description: String,
    val fontFamily: FontFamily,
)

const val DEFAULT_APP_FONT_FAMILY = "系统"

val APP_FONT_FAMILIES = listOf(
    AppFontFamilyOption("系统", "系统默认", "沿用应用主题的默认字体族。", FontFamily.Default),
    AppFontFamilyOption("无衬线", "无衬线", "使用 Compose 提供的 Sans Serif 通用字体族。", FontFamily.SansSerif),
)

/** Unknown keys fall back to the system default family. */
fun appFontFamily(key: String): FontFamily =
    APP_FONT_FAMILIES.firstOrNull { it.key == key }?.fontFamily ?: FontFamily.Default

/** Content-level interface scale for density-aware components. */
val LocalInterfaceScale = staticCompositionLocalOf { 1f }

private fun adjustedWeight(base: FontWeight, mode: String): FontWeight {
    val delta = when (mode) {
        "轻" -> -100
        "粗" -> 100
        else -> 0
    }
    return FontWeight((base.weight + delta).coerceIn(100, 900))
}

private fun scaledTextStyle(
    fontSize: Float,
    lineHeight: Float,
    fontWeight: FontWeight,
    scale: Float,
    weightMode: String,
    fontFamily: FontFamily,
): TextStyle = TextStyle(
    fontFamily = fontFamily,
    fontWeight = adjustedWeight(fontWeight, weightMode),
    fontSize = (fontSize * scale).sp,
    lineHeight = (lineHeight * scale).sp,
)

private fun scaledTypography(fontSizeScale: Float, fontWeightMode: String, fontFamily: FontFamily): Typography {
    val scale = fontSizeScale.coerceIn(0.5f, 2f)
    return Typography(
        displaySmall = scaledTextStyle(36f, 44f, FontWeight.Medium, scale, fontWeightMode, fontFamily),
        headlineLarge = scaledTextStyle(32f, 40f, FontWeight.SemiBold, scale, fontWeightMode, fontFamily),
        headlineMedium = scaledTextStyle(28f, 36f, FontWeight.SemiBold, scale, fontWeightMode, fontFamily),
        headlineSmall = scaledTextStyle(24f, 32f, FontWeight.SemiBold, scale, fontWeightMode, fontFamily),
        titleLarge = scaledTextStyle(20f, 28f, FontWeight.Medium, scale, fontWeightMode, fontFamily),
        titleMedium = scaledTextStyle(17f, 25f, FontWeight.Medium, scale, fontWeightMode, fontFamily),
        titleSmall = scaledTextStyle(15f, 22f, FontWeight.Medium, scale, fontWeightMode, fontFamily),
        bodyLarge = scaledTextStyle(16f, 25f, FontWeight.Normal, scale, fontWeightMode, fontFamily),
        bodyMedium = scaledTextStyle(14f, 22f, FontWeight.Normal, scale, fontWeightMode, fontFamily),
        bodySmall = scaledTextStyle(12f, 19f, FontWeight.Normal, scale, fontWeightMode, fontFamily),
        labelLarge = scaledTextStyle(14f, 20f, FontWeight.Medium, scale, fontWeightMode, fontFamily),
        labelMedium = scaledTextStyle(12f, 18f, FontWeight.Medium, scale, fontWeightMode, fontFamily),
        labelSmall = scaledTextStyle(11f, 16f, FontWeight.Medium, scale, fontWeightMode, fontFamily),
    )
}

/** Apple-style content hierarchy with independently implemented Android glass controls. */
@Composable
fun FishTheme(
    themeMode: String = "system",
    accentColorKey: String = "blue",
    fontSizeScale: Float = 1f,
    fontWeightMode: String = "标准",
    fontFamilyKey: String = DEFAULT_APP_FONT_FAMILY,
    interfaceScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode.lowercase()) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val safeInterfaceScale = interfaceScale.coerceIn(0.75f, 1.5f)
    val accent = when (accentColorKey) {
        "purple" -> if (dark) Color(0xFFB69CFF) else Color(0xFF6D3BFF)
        "green" -> if (dark) Color(0xFF62D79A) else Color(0xFF168A4A)
        "orange" -> if (dark) Color(0xFFFFB65C) else Color(0xFFE26D00)
        "pink" -> if (dark) Color(0xFFFF8DA5) else Color(0xFFD81B48)
        else -> if (dark) Color(0xFF64B5FF) else Color(0xFF007AFF)
    }
    val themedColors = (if (dark) DarkColors else LightColors).copy(
        primary = accent,
        // Accent glass controls keep white text and symbols in both themes.
        onPrimary = Color.White,
        primaryContainer = accent.copy(alpha = if (dark) .28f else .14f),
        onPrimaryContainer = if (dark) Color.White else accent.copy(alpha = .95f),
        tertiary = accent.copy(alpha = .82f),
    )
    val baseDensity = LocalDensity.current
    val contentDensity = Density(
        density = baseDensity.density,
        fontScale = baseDensity.fontScale * safeInterfaceScale,
    )
    CompositionLocalProvider(
        LocalInterfaceScale provides safeInterfaceScale,
        LocalDensity provides contentDensity,
    ) {
        MaterialTheme(
            colorScheme = themedColors,
            typography = scaledTypography(fontSizeScale, fontWeightMode, appFontFamily(fontFamilyKey)),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(12.dp),
                small = RoundedCornerShape(16.dp),
                medium = RoundedCornerShape(24.dp),
                large = RoundedCornerShape(28.dp),
                extraLarge = RoundedCornerShape(32.dp),
            ),
            content = content,
        )
    }
}
