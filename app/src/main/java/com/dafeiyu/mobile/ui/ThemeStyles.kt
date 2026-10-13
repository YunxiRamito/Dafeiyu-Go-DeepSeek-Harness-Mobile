package com.dafeiyu.mobile.ui

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import com.dafeiyu.mobile.glasslab.LabDocument
import com.dafeiyu.mobile.glasslab.LabParameters
import com.dafeiyu.mobile.ui.components.GlassDebugTuning
import com.dafeiyu.mobile.ui.components.GlassThemeStyle
import com.dafeiyu.mobile.ui.components.GlassThemeStyles

/** Appearance adapter only: imported widths, icons, disabled state and hit targets do not alter app actions. */
internal fun LabDocument.nativeThemeStyles(): GlassThemeStyles = GlassThemeStyles(
    light = light.mapValues { (_, parameters) -> parameters.nativeThemeStyle() },
    dark = dark.mapValues { (_, parameters) -> parameters.nativeThemeStyle() },
)

internal fun LabParameters.themeColor(key: String): Color = Color(android.graphics.Color.parseColor(colors.getValue(key)))

internal fun LabParameters.nativeThemeStyle(): GlassThemeStyle {
    val tuning = GlassDebugTuning(
        blurDp = this["blurDp"], refractionScale = this["refractionScale"],
        refractionFalloff = this["refractionFalloff"], dispersion = this["dispersion"], bevelScale = this["bevelScale"],
        reflectionStrength = this["reflectionStrength"], lightAngleDegrees = this["lightAngleDegrees"],
        highlightStrength = this["highlightStrength"], rimStrength = this["rimStrength"],
        borderWidthDp = this["borderWidthDp"], borderOpacity = this["borderOpacity"], saturation = this["saturation"],
        dimAmount = this["dimAmount"], surfaceAlpha = this["surfaceAlpha"], pressScale = this["pressScale"],
        dragStretch = this["dragStretch"], dragCompression = this["dragCompression"], dragDistanceDp = this["dragDistanceDp"],
        dragResistance = this["dragResistance"], pressMillis = this["pressMillis"], releaseMillis = this["releaseMillis"],
        shadowBlurDp = this["shadowBlurDp"], shadowSpreadDp = this["shadowSpreadDp"],
        shadowOffsetXDp = this["shadowOffsetXDp"], shadowOffsetYDp = this["shadowOffsetYDp"], shadowOpacity = this["shadowOpacity"],
        shadowPressScale = this["shadowPressScale"], springDamping = this["springDamping"], springStiffness = this["springStiffness"],
        highlightColor = themeColor("highlight"), borderColor = themeColor("border"), shadowColor = themeColor("shadow"),
        glassThicknessDp = this["glassThicknessDp"], innerRefractionStrength = this["innerRefractionStrength"],
        innerRefractionWidthDp = this["innerRefractionWidthDp"], innerDispersion = this["innerDispersion"],
        trackingLightWidthDp = this["trackingLightWidthDp"], trackingLightIntensity = this["trackingLightIntensity"],
        pressDarken = this["pressDarken"], bridgeReleaseMillis = this["bridgeReleaseMillis"], bridgeReachDp = this["bridgeReachDp"],
        pressEffect = pressEffect, trackingLightColor = themeColor("trackingLight"),
    ).sanitized()
    val blend = when (iconBlend) {
        "multiply" -> BlendMode.Multiply
        "screen" -> BlendMode.Screen
        "overlay" -> BlendMode.Overlay
        "difference" -> BlendMode.Difference
        "plus" -> BlendMode.Plus
        "srcIn" -> BlendMode.SrcIn
        else -> BlendMode.SrcOver
    }
    return GlassThemeStyle(tuning, themeColor("surface").let { it.copy(alpha = it.alpha * this["tintAlpha"]) },
        themeColor("text").let { it.copy(alpha = it.alpha * this["textAlpha"]) },
        themeColor("icon").let { it.copy(alpha = it.alpha * this["iconAlpha"]) }, blend,
        roundness = this["roundness"],
        glassEnabled = glassEnabled, motionEnabled = motionEnabled, springEnabled = springEnabled)
}
