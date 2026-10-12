package com.dafeiyu.mobile.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dafeiyu.mobile.data.*
import com.dafeiyu.mobile.ui.components.ChatMarkdown
import com.dafeiyu.mobile.ui.components.FishButton
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.GlassMotion
import com.dafeiyu.mobile.ui.components.LocalReduceGlassMotion
import com.dafeiyu.mobile.ui.components.StreamRevealText
import com.dafeiyu.mobile.ui.components.applyButtonDeformation
import java.net.URI

@Composable
internal fun AssistantActivityFeed(
    messageId: Long,
    snapshot: AssistantStreamSnapshot,
    showReasoning: Boolean,
    defaultExpanded: Boolean,
    onRetry: (() -> Unit)? = null,
    retryEnabled: Boolean = true,
) {
    val summary = remember(snapshot) { compactActivitySummary(snapshot) }
    val history = remember(snapshot) { compactActivityHistory(snapshot) }
    if (summary.rows.isNotEmpty() || (showReasoning &&
            summary.timeline.any { it is CompactActivityEntry.Reasoning }) || history.isNotEmpty()) {
        ActivityDisclosure(messageId, "all", compactActivityTitle(snapshot, summary), "terminal",
            null, false, titleMaxLines = 2, running = summary.isRunning,
            agentCount = summary.agentCount) {
            summary.timeline.forEach { entry ->
                key(entry.id) {
                    when (entry) {
                        is CompactActivityEntry.Reasoning -> if (showReasoning) {
                            val reference = entry.reference
                            val block = entry.block
                            val label = if (block.kind == AssistantReasoningKind.SUMMARY) "思考摘要" else "思考"
                            ActivityDisclosure(messageId, entry.id,
                                "$label · ${latestPublicThinkingSentence(block.text)}",
                                "model", null, false, titleMaxLines = 1) {
                                SelectionContainer {
                                    val streaming = !snapshot.isFinal && !block.isComplete &&
                                        snapshot.messages.any { it.messageId == reference.messageId &&
                                            it.status == AssistantMessageStatus.IN_PROGRESS }
                                    StreamRevealText(block.text, streaming, "$messageId-${entry.id}",
                                        modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        is CompactActivityEntry.Activity -> CompactActivityEventRow(messageId, entry.row, snapshot)
                    }
                }
            }
            history.forEachIndexed { index, message ->
                key("history-${message.messageId}") {
                    Text("过程正文 · ${index + 1}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    message.blocks.filterIsInstance<AssistantContentBlockSnapshot.Text>()
                        .filter { it.text.isNotBlank() }
                        .forEach { block ->
                            key(block.blockId) {
                                SelectionContainer {
                                    ChatMarkdown(block.text,
                                        streaming = !snapshot.isFinal && !block.isComplete &&
                                            message.status == AssistantMessageStatus.IN_PROGRESS,
                                        contentKey = "$messageId-history-${message.messageId}-${block.blockId}",
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                }
            }
        }
    }
    if (snapshot.citations.isNotEmpty()) {
        ActivityDisclosure(messageId, "citations", "来源", "link",
            "${snapshot.citations.size}", defaultExpanded) {
            snapshot.citations.forEach { reference ->
                key(reference.citation.id) {
                    ActivityLink(reference.citation.title ?: reference.citation.url ?: "引用", reference.citation.url)
                }
            }
        }
    }
    snapshot.messages.forEach { message ->
        when (message.status) {
            AssistantMessageStatus.FAILED -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActivityText(message.error ?: "回复失败", error = true, modifier = Modifier.weight(1f))
                    // Only the final failed part of the latest reply can be resent.
                    if (onRetry != null && message === snapshot.messages.last()) {
                        FishButton(onClick = onRetry, accent = false, enabled = retryEnabled,
                            modifier = Modifier.semantics { contentDescription = "重试此回复" }) {
                            Text("重试", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            AssistantMessageStatus.INCOMPLETE -> ActivityText("回复未完成${message.incompleteReason?.let { " · $it" }.orEmpty()}")
            AssistantMessageStatus.CANCELLED -> ActivityText("已停止回复，独立子任务状态以活动记录为准")
            else -> Unit
        }
    }
}

@Composable
private fun CompactActivityEventRow(
    messageId: Long,
    row: CompactActivityRow,
    snapshot: AssistantStreamSnapshot,
) {
    var openedByUser by rememberSaveable(messageId, row.id) { mutableStateOf(false) }
    ActivityDisclosure(messageId, "row-${row.id}", row.headline, row.kind.glyph, null, false,
        onUserToggle = { openedByUser = it },
        headerContent = { CompactActivityEventHeader(row) }) {
        if (row.names.isNotEmpty()) ActivityText(row.names.take(3).joinToString("、") +
            if (row.names.size > 3) " 等 ${row.names.size} 项" else "")
        row.notice?.let { ActivityText(activityReceiptText(it, 512).text, error = row.hasError) }
        if (row.references.size == 1) {
            val receipts = remember(snapshot, row.references, openedByUser) {
                activityReceiptDetails(snapshot, row.references, openedByUser)
            }
            receipts.forEach { ActivityReceiptContent(it) }
        } else if (openedByUser) {
            row.references.forEach { reference ->
                key(reference.kind, reference.id) {
                    ActivityReceiptDisclosure(messageId, reference, row.kind.glyph, snapshot)
                }
            }
        }
    }
}

@Composable
private fun ActivityReceiptDisclosure(
    messageId: Long,
    reference: AssistantActivityReference,
    glyph: String,
    snapshot: AssistantStreamSnapshot,
) {
    val title = activityReceiptTitle(snapshot, reference) ?: return
    var openedByUser by rememberSaveable(messageId, reference.kind, reference.id) { mutableStateOf(false) }
    ActivityDisclosure(messageId, "receipt-${reference.kind}:${reference.id}",
        "$title · ${activityReceiptText(reference.id, 512).text}", glyph, null, false,
        titleMaxLines = 1, onUserToggle = { openedByUser = it }) {
        val receipts = remember(snapshot, reference, openedByUser) {
            activityReceiptDetails(snapshot, listOf(reference), openedByUser)
        }
        receipts.forEach { ActivityReceiptContent(it) }
    }
}

@Composable
private fun RowScope.CompactActivityEventHeader(row: CompactActivityRow) {
    val palette = MaterialTheme.colorScheme
    val leadingWidth = if (row.kind == CompactActivityKind.AGENT)
        ActivityGlyphSize + 13.5.dp * (row.leadingGlyphCount.coerceAtLeast(1) - 1) else ActivityGlyphSize
    Box(Modifier.width(leadingWidth).height(ActivityGlyphSize), contentAlignment = Alignment.CenterStart) {
        if (row.kind == CompactActivityKind.AGENT) {
            repeat(row.leadingGlyphCount) { index ->
                FishGlyph(row.kind.glyph,
                    Modifier.offset(x = (index * 13.5f).dp).size(ActivityGlyphSize), palette.onSurfaceVariant)
            }
        } else {
            FishGlyph(row.kind.glyph, Modifier.size(ActivityGlyphSize), palette.onSurfaceVariant)
        }
    }
    Text(row.headline, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium, color = palette.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(row.statusLabel, Modifier.widthIn(max = 110.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (row.hasError) palette.error else palette.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ActivityReceiptContent(receipt: ActivityReceiptDetails) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${receipt.title} · ${receipt.status}", Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = if (receipt.hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        ReceiptCopyAction("复制回执") { activityReceiptCopyText(receipt) }
    }
    receipt.identifiers.forEach { (label, value) -> ActivityReceiptValue(label, value, monospace = true) }
    receipt.arguments?.let { value -> ActivityReceiptValue("参数", value, monospace = true, copyable = true) }
    receipt.sections.forEach { section ->
        ActivityReceiptValue(section.label, section.content, monospace = true, copyable = true)
    }
    receipt.sources.forEachIndexed { index, source ->
        ActivityReceiptSourceContent("来源 ${index + 1}", source)
    }
    if (receipt.links.isNotEmpty()) Text("网页内链接", style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    receipt.links.forEach { link -> ActivityReceiptSourceContent("链接", link) }
    receipt.metadata.forEach { ActivityText(it) }
}

@Composable
private fun ActivityReceiptValue(
    label: String,
    value: ActivityReceiptText,
    monospace: Boolean = false,
    copyable: Boolean = false,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (copyable) ReceiptCopyAction("复制$label") { value.text }
        }
        SelectionContainer {
            Text(value.text, Modifier.fillMaxWidth().heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val notices = listOfNotNull(
            "显示正文已截断，复制仅包含显示部分".takeIf { value.clipped },
            "控制字符已替换".takeIf { value.sanitized },
            "敏感字段已隐藏".takeIf { value.redacted },
        )
        if (notices.isNotEmpty()) ActivityText(notices.joinToString(" · "))
    }
}

@Composable
private fun ActivityReceiptSourceContent(label: String, source: ActivityReceiptSource) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ActivityReceiptValue(label, source.title)
        if (source.url != null) SelectionContainer { ActivityLink(source.url, source.url) }
        else ActivityText("未提供可打开的安全 URL")
        source.publishedAt?.let { ActivityText("发布时间 · $it") }
        source.snippet?.let { ActivityReceiptValue("摘要", it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiptCopyAction(label: String, text: () -> String) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = { clipboard.setText(AnnotatedString(text())) },
            modifier = Modifier.size(48.dp).applyButtonDeformation(circularHit = true, consumeDrag = false)
                .semantics { contentDescription = label }) {
            FishGlyph("document", Modifier.size(17.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val ActivityGlyphSize = 17.dp
private val ActivityRowSpacing = 2.dp

@Composable
private fun ActivityDisclosure(
    messageId: Long,
    activityId: String,
    title: String,
    glyph: String,
    status: String?,
    defaultExpanded: Boolean,
    titleMaxLines: Int = 2,
    running: Boolean? = null,
    agentCount: Int = 0,
    onUserToggle: ((Boolean) -> Unit)? = null,
    headerContent: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(messageId, activityId) { mutableStateOf(running ?: defaultExpanded) }
    var observedRunning by rememberSaveable(messageId, activityId) { mutableStateOf(running) }
    val visible = compactActivityExpanded(expanded, observedRunning, running)
    LaunchedEffect(running) {
        expanded = compactActivityExpanded(expanded, observedRunning, running)
        observedRunning = running
    }
    val palette = MaterialTheme.colorScheme
    val reducedMotion = LocalReduceGlassMotion.current
    val duration = if (reducedMotion) 0 else GlassMotion.MorphMillis
    val rotation by animateFloatAsState(if (visible) 90f else 0f,
        GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion),
        label = "activityDisclosure")
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .applyButtonDeformation(consumeDrag = false)
            .semantics { stateDescription = if (visible) "已展开" else "已收起" }
            .clickable(role = Role.Button) {
                expanded = !visible
                observedRunning = running
                onUserToggle?.invoke(expanded)
            }
            .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (headerContent != null) headerContent()
            else {
                Box(Modifier.size(ActivityGlyphSize),
                    contentAlignment = Alignment.CenterStart) {
                    FishGlyph(glyph, Modifier.size(ActivityGlyphSize), palette.onSurfaceVariant)
                }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium, color = palette.onSurfaceVariant,
                    maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            }
            if (agentCount > 0) Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.semantics {
                    stateDescription = "$agentCount 个子智能体"
                },
            ) {
                FishGlyph("groupchat", Modifier.size(16.dp), palette.onSurfaceVariant)
                Text("$agentCount", style = MaterialTheme.typography.labelSmall,
                    color = palette.onSurfaceVariant, maxLines = 1)
            }
            if (status != null) Text(status, style = MaterialTheme.typography.labelSmall,
                color = palette.onSurfaceVariant, modifier = Modifier.widthIn(max = 90.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            FishGlyph("chevron", Modifier.size(12.dp).graphicsLayer { rotationZ = rotation },
                palette.onSurfaceVariant)
        }
        AnimatedVisibility(visible = visible,
            enter = expandVertically(tween(duration, easing = GlassMotion.EaseOut)) +
                fadeIn(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion)),
            exit = shrinkVertically(tween(duration, easing = GlassMotion.EaseOut)) +
                fadeOut(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion))) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(ActivityRowSpacing), content = content)
        }
    }
}

/** Extract source text only, including the current partial sentence while a delta is arriving. */
internal fun latestPublicThinkingSentence(text: String, maximum: Int = 512): String {
    val end = text.indexOfLast { !it.isWhitespace() } + 1
    if (end == 0) return ""
    var scan = end - 1
    val closers = "\"'”’」』）)]}"
    while (scan >= 0 && (text[scan] in "。！？!?." || text[scan] in closers)) scan--
    while (scan >= 0) {
        val character = text[scan]
        val decimalPoint = character == '.' && scan > 0 && scan + 1 < end &&
            text[scan - 1].isDigit() && text[scan + 1].isDigit()
        if (character in "。！？!?\n\r" || (character == '.' && !decimalPoint)) break
        scan--
    }
    var start = scan + 1
    while (start < end && (text[start].isWhitespace() || (text[start] in closers &&
            start + 1 < end && text[start + 1].isWhitespace()))) start++
    var previewEnd = start + minOf(end - start, maximum.coerceIn(1, 512))
    if (previewEnd < end && text[previewEnd - 1].isHighSurrogate() && text[previewEnd].isLowSurrogate()) previewEnd--
    return text.substring(start, previewEnd).trimEnd() + if (previewEnd < end) "…" else ""
}

@Composable
private fun ActivityText(text: String, monospace: Boolean = false, error: Boolean = false, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    SelectionContainer {
        Text(text, modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActivityLink(title: String, url: String?) {
    val uriHandler = LocalUriHandler.current
    val safeUrl = remember(url) {
        url?.takeIf {
            runCatching { URI(it).let { uri -> uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() } }
                .getOrDefault(false)
        }
    }
    Text(title, Modifier.fillMaxWidth().then(
        if (safeUrl != null) Modifier.clickable(role = Role.Button) {
            runCatching { uriHandler.openUri(safeUrl) }
        }.heightIn(min = 48.dp).padding(vertical = 6.dp) else Modifier),
        style = MaterialTheme.typography.bodySmall,
        color = if (safeUrl != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
}

