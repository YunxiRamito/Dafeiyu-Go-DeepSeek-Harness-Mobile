package com.dafeiyu.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.LocalSettingsPageTransition
import com.dafeiyu.mobile.ui.components.LocalSettingsGroupGeometry
import com.dafeiyu.mobile.ui.components.LocalGlassHaptics


/** Scroll state exposed to the root toolbar; content owns the large header, root owns back. */
@Stable
internal class SettingsNavigationState {
    var title by mutableStateOf("设置")
        private set
    var collapseProgress by mutableStateOf(0f)
        private set
    var titleBoundsInWindow by mutableStateOf<Rect?>(null)
        private set
    var expandedFontSizeSp by mutableStateOf(32f)
        private set
    var scrollOffsetPx by mutableStateOf(0f)
        private set
    fun update(title: String, progress: Float, scrollOffsetPx: Float? = null) {
        if (this.title != title) titleBoundsInWindow = null
        this.title = title
        collapseProgress = progress.coerceIn(0f, 1f)
        if (scrollOffsetPx != null) this.scrollOffsetPx = scrollOffsetPx.coerceAtLeast(0f)
    }
    fun recordTitle(title: String, visibleBounds: Rect, fontSizeSp: Float, scrollOffsetPx: Float) {
        // Layout can run before the publishing SideEffect on the first frame.
        this.title = title
        // Restore unscrolled geometry so the one overlay title follows a stable trajectory.
        titleBoundsInWindow = Rect(visibleBounds.left, visibleBounds.top + scrollOffsetPx,
            visibleBounds.right, visibleBounds.bottom + scrollOffsetPx)
        expandedFontSizeSp = fontSizeSp
    }
    fun reset() { collapseProgress = 0f; titleBoundsInWindow = null; scrollOffsetPx = 0f }
}

internal val LocalSettingsNavigationState = staticCompositionLocalOf<SettingsNavigationState?> { null }
internal val LocalPageHeaderInset = staticCompositionLocalOf { 0.dp }

@Composable
internal fun rememberSettingsNavigationState(): SettingsNavigationState = remember { SettingsNavigationState() }

@Composable
internal fun PublishSettingsNavigation(title: String, collapseProgress: Float, scrollOffsetPx: Float? = null) {
    val navigation = LocalSettingsNavigationState.current
    SideEffect { navigation?.update(title, collapseProgress, scrollOffsetPx) }
}

@Stable
internal data class CollapsedTitleState(val progress: Float, val collapsedTitle: String)

@Composable
internal fun rememberCollapsedTitleState(scrollValue: Int, title: String, collapseDistancePx: Float): CollapsedTitleState {
    val progress = (scrollValue / collapseDistancePx.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return CollapsedTitleState(progress = progress, collapsedTitle = title)
}

@Composable
internal fun PageHeader(title: String, subtitle: String, action: (@Composable () -> Unit)? = null,
                        collapseProgress: Float = 0f) {
    val progress = collapseProgress.coerceIn(0f, 1f)
    val inset = LocalPageHeaderInset.current
    val navigation = LocalSettingsNavigationState.current
    PublishSettingsNavigation(title, progress)
    Column(Modifier.fillMaxWidth().padding(top = inset + 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).onGloballyPositioned { coordinates ->
                if (navigation != null) {
                    val origin = coordinates.positionInWindow()
                    navigation.recordTitle(title, Rect(origin.x, origin.y, origin.x + coordinates.size.width,
                        origin.y + coordinates.size.height), 32f, navigation.scrollOffsetPx)
                }
            }.then(if (navigation != null) Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics {} else Modifier),
                fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            action?.invoke()
        }
        Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 5.dp).graphicsLayer { alpha = 1f - progress }, lineHeight = 20.sp)
    }
}

@Composable
internal fun SettingRow(glyph: String, title: String, subtitle: String? = null, trailing: String? = null,
                        targetRoute: String? = null, onClick: () -> Unit) {
    val navigation = LocalSettingsPageTransition.current
    val group = LocalSettingsGroupGeometry.current
    val haptics = LocalGlassHaptics.current
    var rowBounds by remember { mutableStateOf<Rect?>(null) }
    Row(Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
        val position = coordinates.positionInWindow()
        rowBounds = Rect(position.x, position.y, position.x + coordinates.size.width, position.y + coordinates.size.height)
    }.clickable(onClick = {
        val bounds = rowBounds
        if (targetRoute != null && bounds != null) navigation?.recordSource(targetRoute, bounds, group?.boundsInWindow, bounds.height / 2f)
        haptics.confirm()
        onClick()
    }).heightIn(min = if (subtitle == null) 56.dp else 72.dp).padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            FishGlyph(if (glyph == "usage") "model" else glyph, Modifier.size(23.dp), MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        // Trailing value: one line, right-aligned, ellipsized; never pushes the title onto two lines.
        if (trailing != null) Text(trailing, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 120.dp))
        FishGlyph("chevron", Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun TinyLabel(text: String, primary: Boolean = false) {
    val color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = .08f))
            .padding(horizontal = 9.dp, vertical = 5.dp))
}

@Composable
internal fun SubpageHeader(title: String, subtitle: String, onBack: () -> Unit,
                           collapseProgress: Float = 0f) {
    // The root renders the unique return control and the unique moving title.
    @Suppress("UNUSED_VARIABLE") val retainedBack = onBack
    val progress = collapseProgress.coerceIn(0f, 1f)
    val navigation = LocalSettingsNavigationState.current
    PublishSettingsNavigation(title, progress)
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp, top = LocalPageHeaderInset.current + 10.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.onGloballyPositioned { coordinates ->
                if (navigation != null) {
                    val origin = coordinates.positionInWindow()
                    navigation.recordTitle(title, Rect(origin.x, origin.y, origin.x + coordinates.size.width,
                        origin.y + coordinates.size.height), 28f, navigation.scrollOffsetPx)
                }
            }.then(if (navigation != null) Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics {} else Modifier))
        Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 5.dp).graphicsLayer { alpha = 1f - progress }, lineHeight = 18.sp)
    }
}
