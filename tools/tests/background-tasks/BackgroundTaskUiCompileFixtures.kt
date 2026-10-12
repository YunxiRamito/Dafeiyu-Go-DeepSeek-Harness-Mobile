package com.dafeiyu.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Only compile signatures; these are never a running UI or fake execution backend.
class GlassBackdropState
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdropState?> { null }
val LocalFloatingGlassBackdrop = staticCompositionLocalOf<GlassBackdropState?> { null }
val LocalReduceGlassMotion = staticCompositionLocalOf { false }

@Composable
fun GlassSurface(
    backdrop: GlassBackdropState? = LocalGlassBackdrop.current,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.surface.copy(alpha = .54f),
    content: @Composable BoxScope.() -> Unit,
) { Box(modifier, content = content) }

@Composable
fun FishButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = true,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) { Row(modifier, content = content) }

@Composable
fun FishGlyph(name: String, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) = Unit
