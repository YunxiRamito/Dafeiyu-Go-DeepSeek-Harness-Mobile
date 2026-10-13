package com.dafeiyu.mobile.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color

/** Portable material appearance only. Application geometry, icon identity and actions stay owned by callers. */
@Immutable
data class GlassThemeStyle(
    val tuning: GlassDebugTuning,
    val surfaceTint: Color,
    val contentColor: Color,
    val iconColor: Color = contentColor,
    val iconBlendMode: BlendMode = BlendMode.SrcOver,
    val roundness: Float? = null,
    val glassEnabled: Boolean = true,
    val motionEnabled: Boolean = true,
    val springEnabled: Boolean = true,
)

/** Keys are accent, circle, regular and panel. A missing key preserves the production default. */
@Immutable
data class GlassThemeStyles(
    val light: Map<String, GlassThemeStyle>,
    val dark: Map<String, GlassThemeStyle>,
) {
    fun style(type: String, darkTheme: Boolean): GlassThemeStyle? =
        (if (darkTheme) dark else light)[type]
}

val LocalGlassThemeStyles = staticCompositionLocalOf<GlassThemeStyles?> { null }

/** The selected surface provides this to its foreground and modifier, including FishGlyph. */
val LocalGlassThemeStyle = staticCompositionLocalOf<GlassThemeStyle?> { null }

internal fun glassThemeType(profile: GlassMaterialProfile, tint: Color, circular: Boolean,
    hasDeformation: Boolean, requestedType: String? = null): String {
    if (requestedType in setOf("accent", "circle", "regular", "panel")) return requireNotNull(requestedType)
    val chroma = maxOf(tint.red, tint.green, tint.blue) - minOf(tint.red, tint.green, tint.blue)
    if (profile == GlassMaterialProfile.AccentButton ||
        ((profile.buttonStyle || hasDeformation) && tint.alpha > .24f && chroma > .08f)) return "accent"
    if (circular) return "circle"
    return if (profile.buttonStyle || hasDeformation) "regular" else "panel"
}
