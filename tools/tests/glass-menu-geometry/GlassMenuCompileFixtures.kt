@file:Suppress("UNUSED_PARAMETER")

package com.dafeiyu.mobile.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Rendering signatures only; the real menu compiles and only its pure geometry runs.
class GlassBackdropState
enum class GlassMaterialProfile { FloatingMenu }
val LocalGlassMaterialProfile = staticCompositionLocalOf { GlassMaterialProfile.FloatingMenu }
val LocalPageGlassBackdrop = staticCompositionLocalOf<Any?> { null }
val LocalFloatingGlassBackdrop = staticCompositionLocalOf<GlassBackdropState?> { null }
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdropState?> { null }
val LocalReduceGlassMotion = staticCompositionLocalOf { false }

@Composable
fun GlassSurface(
    backdrop: GlassBackdropState? = null,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    tint: Color = Color.White,
    reduceTransparency: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) = Unit
