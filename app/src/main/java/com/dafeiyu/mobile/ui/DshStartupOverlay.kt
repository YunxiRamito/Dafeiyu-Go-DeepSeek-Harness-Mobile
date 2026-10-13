package com.dafeiyu.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dafeiyu.mobile.ui.components.FishButton
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.GlassMotion
import com.dafeiyu.mobile.ui.components.LocalReduceGlassMotion
import com.dafeiyu.mobile.ui.components.LocalReduceGlassTransparency

/** Native preparation only; successful presentation belongs to the webpage. */
@Composable
internal fun DshStartupOverlay(visible: Boolean, title: String, detail: String,
    failed: Boolean, online: Boolean, serviceReady: Boolean, motionReduced: Boolean,
    onRetry: () -> Unit, onSettings: () -> Unit, onExit: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    val reduced = motionReduced || LocalReduceGlassMotion.current
    val flatMaterial = LocalReduceGlassTransparency.current
    val density = LocalDensity.current
    val ringStroke = remember(density.density) { Stroke(width = with(density) { 2.dp.toPx() }, cap = StrokeCap.Round) }
    val background = remember(palette.background, palette.primary, flatMaterial) {
        Brush.verticalGradient(listOf(
            if (flatMaterial) palette.background else palette.primary.copy(alpha = .055f)
                .compositeOver(palette.background),
            palette.background,
        ))
    }
    AnimatedVisibility(visible = visible, modifier = Modifier.fillMaxSize(),
        enter = fadeIn(GlassMotion.scalar(GlassMotion.ReleaseMillis, reduced)),
        // Release promptly so the actual webpage's same-version 700ms reveal is
        // visible. The handshake, rather than this animation, chooses the time.
        exit = fadeOut(GlassMotion.scalar(GlassMotion.PressMillis, reduced))) {
        Box(Modifier.fillMaxSize().background(background).testTag("dsh-startup-overlay")
            .semantics {
                stateDescription = if (failed) "DSH 启动未完成" else "等待 DSH 网页就绪"
            }) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                val orbit = if (visible && !failed && !reduced) {
                    val transition = rememberInfiniteTransition(label = "dshPreparationOrbit")
                    transition.animateFloat(0f, 360f,
                        animationSpec = infiniteRepeatable(
                            tween(GlassMotion.ReleaseMillis * 5, easing = GlassMotion.EaseOut),
                            RepeatMode.Restart), label = "dshPreparationPhase")
                } else remember { mutableFloatStateOf(0f) }
                val ringColor = palette.primary.copy(alpha = if (flatMaterial) .8f else .65f)
                val ringTrack = palette.onBackground.copy(alpha = .08f)
                Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val inset = 3.dp.toPx()
                        val extent = size.width - inset * 2
                        drawArc(ringTrack, 0f, 360f, false, Offset(inset, inset),
                            androidx.compose.ui.geometry.Size(extent, extent), style = ringStroke)
                        if (!failed) drawArc(ringColor, orbit.value - 90f, 78f, false, Offset(inset, inset),
                            androidx.compose.ui.geometry.Size(extent, extent), style = ringStroke)
                    }
                    Box(Modifier.size(66.dp).background(palette.primary.copy(alpha = .08f), CircleShape),
                        contentAlignment = Alignment.Center) {
                        FishGlyph(if (failed) "info-circle" else "app-icon", Modifier.size(38.dp),
                            tint = if (failed) palette.error else palette.primary)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(if (online) "电脑 DSH" else "本机 DSH",
                    style = MaterialTheme.typography.labelLarge, color = palette.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(detail, modifier = Modifier.widthIn(max = 400.dp)
                    .testTag(if (failed) "dsh-startup-failure" else "dsh-startup-detail")
                    .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium, color = palette.onSurfaceVariant,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Row(Modifier.background(palette.surfaceVariant.copy(alpha = .5f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    FishGlyph(if (serviceReady) "check" else if (online) "link" else "terminal",
                        Modifier.size(15.dp), tint = palette.onSurfaceVariant)
                    Text(if (failed) "等待重试" else if (serviceReady) "服务已连接 · 网页准备中" else if (online) "正在建立加密网页通道"
                        else "正在准备运行环境", style = MaterialTheme.typography.labelMedium,
                        color = palette.onSurfaceVariant)
                }
                Spacer(Modifier.height(32.dp))
                AnimatedVisibility(visible = failed,
                    enter = fadeIn(GlassMotion.scalar(GlassMotion.ReleaseMillis, reduced)),
                    exit = fadeOut(GlassMotion.scalar(GlassMotion.PressMillis, reduced))) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FishButton(onClick = onRetry, modifier = Modifier.widthIn(max = 320.dp)
                            .fillMaxWidth().testTag("dsh-startup-retry")) {
                            FishGlyph("refresh", Modifier.size(18.dp))
                            Text("重试")
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
                FishButton(onClick = onSettings, accent = false,
                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().testTag("dsh-startup-settings")) {
                    FishGlyph("settings", Modifier.size(18.dp))
                    Text("DSH 设置")
                }
                Spacer(Modifier.height(10.dp))
                FishButton(onClick = onExit, accent = false,
                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().testTag("dsh-startup-exit")) {
                    FishGlyph("back", Modifier.size(18.dp))
                    Text("返回设置")
                }
            }
        }
    }
}
