package com.dafeiyu.mobile.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** Compile the consuming screen API against real shared components, including nested Material controls. */
@Composable
internal fun liquidGlassApiCheck(strength: Float, click: () -> Unit) {
    val deformation = rememberGlassButtonDeformationState()
    val second = rememberGlassButtonDeformationState()
    CompositionLocalProvider(LocalReduceGlassMotion provides true, LocalReduceGlassTransparency provides true) {
        GlassSurface(buttonDeformation = deformation,
            modifier = Modifier.applyButtonDeformation(state = deformation, circularHit = true)) {
            IconButton(onClick = click) { FishGlyph("add") }
        }
        FishButton(onClick = click, enabled = false) { FishGlyph("send") }
        FishButton(onClick = click, consumeDrag = false) { FishGlyph("document") }
        FishButton(onClick = click, consumeDrag = true) { FishGlyph("send") }
        LiquidGlassButtonPair(deformation, second, firstAlpha = { strength }, secondAlpha = { 1f }) { drawMaterial ->
            GlassSurface(buttonDeformation = deformation, drawMaterial = drawMaterial,
                modifier = Modifier.applyButtonDeformation(state = deformation, circularHit = true,
                    drawShadow = drawMaterial)) {
                IconButton(onClick = click) { FishGlyph("add") }
            }
            GlassSurface(buttonDeformation = second, drawMaterial = drawMaterial,
                modifier = Modifier.applyButtonDeformation(state = second, circularHit = true,
                    drawShadow = drawMaterial)) {
                IconButton(onClick = click) { FishGlyph("groupchat") }
            }
        }
        LiquidGlassBridge(
            modifier = Modifier.width(12.dp).height(34.dp),
            gap = 4.dp, strength = sin(strength * PI).toFloat(), tint = Color.White,
        )
    }
}
