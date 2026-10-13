package com.dafeiyu.mobile.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dafeiyu.mobile.data.ThemeDocuments
import com.dafeiyu.mobile.data.ThemeState
import com.dafeiyu.mobile.glasslab.LabDocument
import com.dafeiyu.mobile.ui.components.*

val LocalAppThemeState = staticCompositionLocalOf<ThemeState?> { null }

@Composable
internal fun ThemesContent() {
    val state = LocalAppThemeState.current
    if (state == null) { DetailCard { Hint("主题管理暂不可用，请重新打开 Dafeiyu-GO。") }; return }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { state.importTheme(it) }
    val context = LocalContext.current
    val reduced = LocalReduceGlassMotion.current
    val enabled = !state.isLoading && !state.busy
    DetailCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("我的主题", style = MaterialTheme.typography.titleMedium)
                Hint("当前使用 · ${state.activeTitle}")
            }
            TextButton(onClick = { import.launch(arrayOf(ThemeDocuments.MIME_TYPE, "application/json", "text/plain", "application/octet-stream")) },
                enabled = enabled, modifier = Modifier.testTag("theme-import")) { Text("导入文件") }
        }
        Hint("从 Dafeiyu调色盘保存完整参数后，可以直接在这里应用。按钮布局与功能沿用 Dafeiyu-GO。")
        TextButton(onClick = {
            val launch = context.packageManager.getLaunchIntentForPackage("com.dafeiyu.mobile.glasslab")
                ?: context.packageManager.getLaunchIntentForPackage("com.dafeiyu.mobile.glasslab.dev")
            if (launch == null) state.report("请先安装 Dafeiyu调色盘，也可使用“导入文件”选择已导出的 JSON。")
            else try { context.startActivity(launch) }
            catch (_: ActivityNotFoundException) { state.report("Dafeiyu调色盘未安装或无法打开。") }
            catch (_: SecurityException) { state.report("系统暂时不允许打开 Dafeiyu调色盘。") }
        }, enabled = enabled, modifier = Modifier.testTag("theme-open-palette")) { Text("打开 Dafeiyu调色盘") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemeCard("默认", "Dafeiyu-GO 原生材质", null, state.selection.activeId == ThemeDocuments.DEFAULT_ID,
                if (state.selection.activeId == ThemeDocuments.DEFAULT_ID) "使用中" else "使用", enabled,
                Modifier.weight(1f).testTag("theme-default")) { state.select(ThemeDocuments.DEFAULT_ID) }
            val custom = state.selection.custom
            if (custom != null) ThemeCard("自定义", "来自 Dafeiyu调色盘", custom.document,
                state.selection.activeId == ThemeDocuments.CUSTOM_ID,
                if (state.selection.activeId == ThemeDocuments.CUSTOM_ID) "使用中" else "使用", enabled,
                Modifier.weight(1f).testTag("theme-custom")) { state.select(ThemeDocuments.CUSTOM_ID) }
            else Spacer(Modifier.weight(1f))
        }
        TextButton(onClick = { state.select(ThemeDocuments.DEFAULT_ID) },
            enabled = enabled && state.selection.activeId != ThemeDocuments.DEFAULT_ID,
            modifier = Modifier.testTag("theme-reset")) { Text("恢复默认主题") }
        AnimatedVisibility(state.message != null, enter = fadeIn(GlassMotion.scalar(GlassMotion.PressMillis, reduced)),
            exit = fadeOut(GlassMotion.scalar(GlassMotion.PressMillis, reduced))) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Hint(state.message.orEmpty())
                if (state.importCancellable) TextButton(onClick = state::cancelImport) { Text("取消导入") }
            }
        }
        if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.downloadProgress?.let { progress ->
            Column {
                val total = progress.totalBytes
                if (total != null && total > 0) LinearProgressIndicator(progress = {
                    (progress.receivedBytes.toFloat() / total).coerceIn(0f, 1f)
                }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                Hint("已下载 ${progress.receivedBytes / 1024} KiB · 摘要校验后可使用")
                TextButton(onClick = state::cancelDownload) { Text("取消下载") }
            }
        }
    }
    LaunchedEffect(state) { if (state.marketAvailable && state.marketPage == null) state.refreshMarket(1) }
    val catalogIds = state.marketPage?.items.orEmpty().map { it.id }.toSet()
    val remaining = state.selection.installed.filterNot { it.id in catalogIds }
    if (remaining.isNotEmpty()) DetailCard {
        Text("已下载", style = MaterialTheme.typography.titleMedium)
        remaining.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { theme -> ThemeCard(theme.title, "已保存 · 可离线使用", theme.document,
                    state.selection.activeId == theme.id, if (state.selection.activeId == theme.id) "使用中" else "使用",
                    enabled, Modifier.weight(1f).testTag("theme-installed-${theme.id}")) { state.select(theme.id) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    DetailCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("主题市场", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { state.refreshMarket() }, enabled = !state.marketLoading && state.marketAvailable,
                modifier = Modifier.testTag("theme-market-refresh")) { Text("刷新") }
        }
        if (!state.marketAvailable) Hint("主题市场暂时离线。已下载的主题可继续使用，服务恢复后再获取新主题。")
        if (state.marketLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val page = state.marketPage
        if (page != null) {
            page.items.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { entry ->
                        val installed = state.selection.installed.firstOrNull { it.id == entry.id }
                        val current = installed?.sha256 == entry.sha256 && installed.revision == entry.revision
                        ThemeCard(entry.title, entry.description,
                            installed?.document?.takeIf { current } ?: entry.preview,
                            state.selection.activeId == entry.id,
                            when { current && state.selection.activeId == entry.id -> "使用中"; current -> "使用"; installed != null -> "更新"; else -> "下载" },
                            enabled && (current || state.marketAvailable), Modifier.weight(1f).testTag("theme-market-${entry.id}")) {
                            if (current) state.select(entry.id) else state.download(entry)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (page.items.isEmpty()) Hint("暂无主题。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { state.refreshMarket(page.page - 1) },
                    enabled = state.marketAvailable && !state.marketLoading && page.page > 1) { Text("上一页") }
                Text("第 ${page.page} 页", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { state.refreshMarket(page.page + 1) },
                    enabled = state.marketAvailable && !state.marketLoading && page.page.toLong() * page.pageSize < page.total) { Text("下一页") }
            }
        } else if (state.marketAvailable) TextButton(onClick = { state.refreshMarket(1) }, enabled = !state.marketLoading) { Text("浏览主题") }
    }
}

@Composable
private fun ThemeCard(title: String, description: String, document: LabDocument?, selected: Boolean,
    action: String, enabled: Boolean, modifier: Modifier, onAction: () -> Unit) {
    val reduced = LocalReduceGlassMotion.current
    Surface(modifier.animateContentSize(tween(if (reduced) 0 else GlassMotion.MorphMillis, easing = GlassMotion.EaseOut)),
        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .6f)) else null) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeCover(document, Modifier.fillMaxWidth().height(105.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = 36.dp))
            TextButton(onClick = onAction, enabled = enabled && action != "使用中", modifier = Modifier.fillMaxWidth()) { Text(action) }
        }
    }
}

/** Real production glass samples its own striped cover; this never records the live DSH WebView. */
@Composable
private fun ThemeCover(document: LabDocument?, modifier: Modifier) {
    val capture = rememberPageBackdropCapture()
    val styles = remember(document) { document?.nativeThemeStyles() }
    val palette = MaterialTheme.colorScheme
    Box(modifier.clip(RoundedCornerShape(13.dp))) {
        Box(Modifier.matchParentSize().graphicsLayer {}.capturePageBackdrop(capture)
            .background(Brush.linearGradient(listOf(Color(0xFFA5DFF7), Color(0xFFE7B8D7), Color(0xFFF5DFC9))))) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
                repeat(4) { Box(Modifier.width(11.dp).fillMaxHeight().background(Color.White.copy(alpha = .45f))) }
            }
        }
        CompositionLocalProvider(LocalPageGlassBackdrop provides capture, LocalGlassThemeStyles provides styles,
            LocalGlassThemeStyle provides null, LocalGlassDebugTuning provides null) {
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                GlassSurface(modifier = Modifier.size(42.dp), cornerRadius = 21.dp,
                    materialProfile = GlassMaterialProfile.Button, styleType = "circle", tint = palette.surface.copy(alpha = .12f)) {
                    FishGlyph("add", Modifier.align(Alignment.Center).size(21.dp))
                }
                GlassSurface(modifier = Modifier.size(42.dp), cornerRadius = 21.dp,
                    materialProfile = GlassMaterialProfile.AccentButton, styleType = "accent", tint = palette.primary.copy(alpha = .78f)) {
                    FishGlyph("send", Modifier.align(Alignment.Center).size(20.dp), Color.White)
                }
            }
        }
    }
}
