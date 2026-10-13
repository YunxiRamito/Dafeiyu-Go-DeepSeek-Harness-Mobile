package com.dafeiyu.mobile.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.math.cos
import kotlin.math.sin

/**
 * Optional, portable renderer inputs used by Material Lab. Production supplies null and retains
 * its profile defaults. Units are dp/degrees/ms; scales and opacities are dimensionless.
 * This value deliberately contains no preference or network state.
 */
@Immutable
data class GlassDebugTuning(
    val blurDp: Float = 2f,
    val refractionScale: Float = 1f,
    val refractionFalloff: Float = 2f,
    val dispersion: Float = .10f,
    val bevelScale: Float = 1f,
    val reflectionStrength: Float = .80f,
    val lightAngleDegrees: Float = 233.1301f,
    val highlightStrength: Float = 1f,
    val rimStrength: Float = .05f,
    val borderWidthDp: Float = 0f,
    val borderOpacity: Float = 0f,
    val saturation: Float = 1.25f,
    val dimAmount: Float = 0f,
    val surfaceAlpha: Float = 1f,
    val pressScale: Float = 1.035f,
    val dragStretch: Float = .045f,
    val dragCompression: Float = .018f,
    val dragDistanceDp: Float = 6f,
    val dragResistance: Float = 1f,
    val pressMillis: Float = GlassMotion.PressMillis.toFloat(),
    val releaseMillis: Float = GlassMotion.ReleaseMillis.toFloat(),
    val shadowBlurDp: Float = 5.76f,
    val shadowSpreadDp: Float = 0f,
    val shadowOffsetXDp: Float = 0f,
    val shadowOffsetYDp: Float = 1.2f,
    val shadowOpacity: Float = .08f,
    val shadowPressScale: Float = 1f,
    val springDamping: Float = .62f,
    val springStiffness: Float = 380f,
    val highlightColor: Color = Color.White,
    val borderColor: Color = Color.White,
    val shadowColor: Color = Color.Black,
    val glassThicknessDp: Float = 7f,
    val innerRefractionStrength: Float = 0f,
    val innerRefractionWidthDp: Float = 4f,
    val innerDispersion: Float = 0f,
    val trackingLightWidthDp: Float = 16f,
    val trackingLightIntensity: Float = 1f,
    val pressDarken: Float = .12f,
    val bridgeReleaseMillis: Float = 260f,
    val bridgeReachDp: Float = 24f,
    val pressEffect: String = "glow",
    val trackingLightColor: Color = Color.White,
) {
    /** Imported profiles can contain NaN/Infinity; none may enter a shader, layout or animation. */
    fun sanitized(): GlassDebugTuning = copy(
        blurDp = blurDp.safe(0f, 48f, 2f),
        refractionScale = refractionScale.safe(0f, 4f, 1f),
        refractionFalloff = refractionFalloff.safe(.05f, 8f, 2f),
        dispersion = dispersion.safe(0f, .8f, .10f),
        bevelScale = bevelScale.safe(0f, 4f, 1f),
        reflectionStrength = reflectionStrength.safe(0f, 4f, .80f),
        lightAngleDegrees = lightAngleDegrees.safe(0f, 360f, 233.1301f),
        highlightStrength = highlightStrength.safe(0f, 4f, 1f),
        rimStrength = rimStrength.safe(0f, 1f, .05f),
        borderWidthDp = borderWidthDp.safe(0f, 12f, 0f),
        borderOpacity = borderOpacity.safe(0f, 1f, 0f),
        saturation = saturation.safe(0f, 3f, 1.25f),
        dimAmount = dimAmount.safe(0f, 1f, 0f),
        surfaceAlpha = surfaceAlpha.safe(0f, 1f, 1f),
        pressScale = pressScale.safe(.5f, 1.5f, 1.035f),
        dragStretch = dragStretch.safe(0f, .5f, .045f),
        dragCompression = dragCompression.safe(0f, .5f, .018f),
        dragDistanceDp = dragDistanceDp.safe(0f, 48f, 6f),
        dragResistance = dragResistance.safe(0f, 4f, 1f),
        pressMillis = pressMillis.safe(0f, 2000f, GlassMotion.PressMillis.toFloat()),
        releaseMillis = releaseMillis.safe(0f, 3000f, GlassMotion.ReleaseMillis.toFloat()),
        shadowBlurDp = shadowBlurDp.safe(0f, 48f, 5.76f),
        shadowSpreadDp = shadowSpreadDp.safe(0f, 32f, 0f),
        shadowOffsetXDp = shadowOffsetXDp.safe(-32f, 32f, 0f),
        shadowOffsetYDp = shadowOffsetYDp.safe(-32f, 32f, 1.2f),
        shadowOpacity = shadowOpacity.safe(0f, 1f, .08f),
        shadowPressScale = shadowPressScale.safe(0f, 2f, 1f),
        springDamping = springDamping.safe(.1f, 1f, .62f),
        springStiffness = springStiffness.safe(50f, 1200f, 380f),
        highlightColor = highlightColor.safe(Color.White),
        borderColor = borderColor.safe(Color.White),
        shadowColor = shadowColor.safe(Color.Black),
        glassThicknessDp = glassThicknessDp.safe(0f, 40f, 7f),
        innerRefractionStrength = innerRefractionStrength.safe(0f, 4f, 0f),
        innerRefractionWidthDp = innerRefractionWidthDp.safe(0f, 32f, 4f),
        innerDispersion = innerDispersion.safe(0f, 1f, 0f),
        trackingLightWidthDp = trackingLightWidthDp.safe(1f, 80f, 16f),
        trackingLightIntensity = trackingLightIntensity.safe(0f, 3f, 1f),
        pressDarken = pressDarken.safe(0f, 1f, .12f),
        bridgeReleaseMillis = bridgeReleaseMillis.safe(0f, 1500f, 260f),
        bridgeReachDp = bridgeReachDp.safe(0f, 96f, 24f),
        pressEffect = if (pressEffect in listOf("none", "darken", "glow", "beam", "lift")) pressEffect else "glow",
        trackingLightColor = trackingLightColor.safe(Color.White),
    )
}

val LocalGlassDebugTuning = staticCompositionLocalOf<GlassDebugTuning?> { null }

private fun Float.safe(low: Float, high: Float, fallback: Float): Float =
    if (isFinite()) coerceIn(low, high) else fallback

private fun Color.safe(fallback: Color): Color = runCatching {
    val rgba = convert(ColorSpaces.Srgb)
    if (!rgba.red.isFinite() || !rgba.green.isFinite() || !rgba.blue.isFinite() || !rgba.alpha.isFinite()) fallback
    else Color(rgba.red.coerceIn(0f, 1f), rgba.green.coerceIn(0f, 1f),
        rgba.blue.coerceIn(0f, 1f), rgba.alpha.coerceIn(0f, 1f))
}.getOrDefault(fallback)

/** The null angle is bit-for-bit the original normalized (-.6, -.8) shader input. */
internal fun glassDebugLightDirection(tuning: GlassDebugTuning?): Offset {
    if (tuning == null) return Offset(-.6f, -.8f)
    val radians = Math.toRadians(tuning.lightAngleDegrees.toDouble())
    return Offset(cos(radians).toFloat(), sin(radians).toFloat())
}

/** Numeric shader mode; none and darken must not retain touch-driven optical brightening. */
internal fun glassPressEffectCode(tuning: GlassDebugTuning?): Float = when (tuning?.pressEffect) {
    "none" -> 0f
    "lift" -> 0f
    "darken" -> 1f
    "beam" -> 3f
    else -> 2f
}
