package com.dafeiyu.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// External glass signatures only; real GeneratedFiles and GlassMotion are compiled.
val LocalReduceGlassMotion = staticCompositionLocalOf { false }
class GlassButtonDeformationState

@Composable
fun rememberGlassButtonDeformationState() = remember { GlassButtonDeformationState() }

fun Modifier.applyButtonDeformation(
    enabled: Boolean = true,
    state: GlassButtonDeformationState? = null,
    circularHit: Boolean = false,
    consumeDrag: Boolean = true,
): Modifier = this

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.surface.copy(alpha = .54f),
    buttonDeformation: GlassButtonDeformationState? = null,
    content: @Composable BoxScope.() -> Unit,
) { Box(modifier, content = content) }

@Composable
fun FishButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = true,
    enabled: Boolean = true,
    consumeDrag: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) { Row(modifier, content = content) }

@Composable
fun FishGlyph(name: String, modifier: Modifier = Modifier) = Unit
