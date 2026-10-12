@file:Suppress("UNUSED_PARAMETER")

package com.dafeiyu.mobile.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** External glass signature only. No fixture composable is executed by JVM checks. */
val LocalReduceGlassMotion = staticCompositionLocalOf { false }

@Composable
fun FishGlyph(name: String, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) = Unit

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.surface,
    content: @Composable BoxScope.() -> Unit,
) = Unit
