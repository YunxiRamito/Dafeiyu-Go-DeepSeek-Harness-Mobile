package com.dafeiyu.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dafeiyu.mobile.ui.AnchoredGlassMenu
import com.dafeiyu.mobile.ui.GlassMenuPlacement
import com.dafeiyu.mobile.ui.rememberModelMenuMorphState

/** The plus shares the same moving-center outline as model/sandbox menus. */
@Composable
fun NewSessionActions(
    onNewConversation: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
    onPairing: () -> Unit = {},
    isPaired: Boolean = false,
    onOverlayProgress: (Float) -> Unit = {},
    dismissRequested: Boolean = false,
    onOpening: () -> Unit = {},
    onSurfaceVisibilityChanged: (Boolean) -> Unit = {},
    deformation: GlassButtonDeformationState = rememberGlassButtonDeformationState(),
    drawLauncherMaterial: Boolean = true,
    onLauncherAlphaChanged: (Float) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(dismissRequested) { if (dismissRequested) expanded = false }
    val state = rememberModelMenuMorphState(expanded)
    val latestLauncherAlphaChanged by rememberUpdatedState(onLauncherAlphaChanged)
    DisposableEffect(state) {
        latestLauncherAlphaChanged(state.launcherAlpha)
        // Clear a retained menu fade when this launcher leaves composition.
        onDispose { latestLauncherAlphaChanged(1f) }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.launcherAlpha }.collect { latestLauncherAlphaChanged(it) }
    }
    val palette = MaterialTheme.colorScheme
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun choose(action: () -> Unit) { pendingAction = action; expanded = false }
    Box(modifier) {
        GlassSurface(buttonDeformation = deformation, drawMaterial = drawLauncherMaterial,
            modifier = Modifier.size(48.dp)
                .applyButtonDeformation(state = deformation, circularHit = true, drawShadow = drawLauncherMaterial)
                .onGloballyPositioned { state.anchorBounds = it.boundsInWindow() }
                .graphicsLayer { alpha = state.launcherAlpha }, cornerRadius = 24.dp,
            tint = palette.surface.copy(alpha = .18f)) {
            IconButton(onClick = {
                if (expanded) expanded = false
                else if (!dismissRequested) {
                    onOpening()
                    expanded = true
                }
            }, modifier = Modifier.matchParentSize().semantics {
                contentDescription = if (expanded) "关闭快捷操作" else "打开快捷操作"
            }) { FishGlyph("add", Modifier.size(23.dp).graphicsLayer { rotationZ = state.progress * 45f }) }
        }
        AnchoredGlassMenu(expanded, state, onDismiss = { expanded = false },
            menuWidth = 220.dp, menuHeight = if (isPaired) 180.dp else 232.dp,
            reduceTransparency = LocalReduceGlassTransparency.current,
            placement = GlassMenuPlacement.BelowEnd,
            onOverlayProgress = onOverlayProgress,
            onSurfaceVisibilityChanged = onSurfaceVisibilityChanged,
            onClosed = { pendingAction?.let { action -> pendingAction = null; action() } },
            anchorContent = {
                CompositionLocalProvider(LocalGlassMaterialProfile provides GlassMaterialProfile.Button) {
                    GlassSurface(backdrop = LocalFloatingGlassBackdrop.current ?: LocalGlassBackdrop.current,
                        modifier = Modifier.fillMaxSize(), cornerRadius = 24.dp,
                        tint = palette.surface.copy(alpha = .18f)) {
                        IconButton(onClick = { expanded = false }, modifier = Modifier.fillMaxSize()
                            .semantics { contentDescription = "关闭快捷操作" }) {
                            FishGlyph("add", Modifier.size(23.dp).graphicsLayer { rotationZ = state.progress * 45f })
                        }
                    }
                }
            },
            sourceContent = {}) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                SessionAction("edit", "新建会话") { choose(onNewConversation) }
                SessionAction("scan", "扫码") { choose(onScan) }
                if (!isPaired) SessionAction("pair", "配对码") { choose(onPairing) }
            }
        }
    }
}

@Composable
private fun SessionAction(icon: String, title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().applyButtonDeformation().clip(RoundedCornerShape(18.dp))
        .clickable(role = Role.Button, onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FishGlyph(icon, Modifier.size(23.dp), MaterialTheme.colorScheme.onSurface)
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
