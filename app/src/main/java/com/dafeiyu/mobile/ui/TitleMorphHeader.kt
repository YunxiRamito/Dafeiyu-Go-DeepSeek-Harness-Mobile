package com.dafeiyu.mobile.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val TitleMorphEasing = com.dafeiyu.mobile.ui.components.GlassMotion.EaseOut

/** Draw once above the page and header material; all coordinates use the same window space. */
@Composable
internal fun TitleMorphHeader(
    navigation: SettingsNavigationState,
    targetLeftInWindowPx: Float,
    targetCenterYInWindowPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.fillMaxSize().onGloballyPositioned { overlayOrigin = it.positionInWindow() }) {
        val source = navigation.titleBoundsInWindow
        if (source != null) {
            val progress = TitleMorphEasing.transform(navigation.collapseProgress)
            val left = source.left + (targetLeftInWindowPx - source.left) * progress
            val centerY = source.center.y + (targetCenterYInWindowPx - source.center.y) * progress
            val fontSizeSp = navigation.expandedFontSizeSp + (17f - navigation.expandedFontSizeSp) * progress
            val expandedLineSp = if (navigation.expandedFontSizeSp >= 32f) 40f else 36f
            val lineHeightSp = expandedLineSp + (22f - expandedLineSp) * progress
            val lineHeightPx = with(density) { lineHeightSp.sp.toPx() }
            val textWidth = with(density) { source.width.toDp() }
            Text(navigation.title,
                modifier = Modifier.offset { IntOffset((left - overlayOrigin.x).roundToInt(),
                    (centerY - overlayOrigin.y - lineHeightPx / 2f).roundToInt()) }
                    .width(textWidth).semantics { heading() },
                fontSize = fontSizeSp.sp, lineHeight = lineHeightSp.sp,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
