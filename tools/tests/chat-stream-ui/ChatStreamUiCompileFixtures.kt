@file:Suppress("UNUSED_PARAMETER")

package com.dafeiyu.mobile.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

// Compile signatures only. Tests never execute these composables or fake a running UI.
val LocalReduceGlassMotion = staticCompositionLocalOf { false }

@Composable
fun FishGlyph(name: String, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) = Unit

// The Markdown runner compiles this API against the real CommonMark renderer separately.
@Composable
fun ChatMarkdown(
    markdown: String,
    streaming: Boolean,
    contentKey: String,
    modifier: Modifier = Modifier,
    style: TextStyle,
    color: Color,
) = Unit

fun Modifier.applyButtonDeformation(
    enabled: Boolean = true,
    state: Any? = null,
    circularHit: Boolean = false,
    consumeDrag: Boolean = true,
): Modifier = this
