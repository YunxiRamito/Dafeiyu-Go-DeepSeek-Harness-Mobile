@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.dafeiyu.mobile.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.commonmark.node.Block
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/**
 * A CommonMark-backed renderer for assistant messages.
 *
 * The host keeps the existing SelectionContainer around this composable. The reveal frontier is
 * message-wide and is based on source offsets, so parsing the same growing message does not
 * restart an animation for each paragraph or code block.
 *
 * The save callback deliberately belongs to the host because only the host owns the SAF launcher.
 */
@Composable
fun ChatMarkdown(
    markdown: String,
    streaming: Boolean,
    contentKey: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onSaveCode: ((code: String, language: String?) -> Unit)? = null,
) {
    val document = remember(markdown) { parseChatMarkdown(markdown) }
    val frontier = rememberStreamRevealFrontier(markdown, streaming, contentKey)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        document.blocks.forEachIndexed { index, block ->
            ChatMarkdownBlock(
                block = block,
                source = markdown,
                frontier = frontier,
                style = style,
                color = color,
                onSaveCode = onSaveCode,
                key = "$contentKey-block-$index",
            )
        }
    }
}

internal data class SourceRange(val start: Int, val end: Int) {
    fun normalized(sourceLength: Int): SourceRange {
        val normalizedStart = start.coerceIn(0, sourceLength)
        return SourceRange(normalizedStart, end.coerceIn(normalizedStart, sourceLength))
    }
}

internal data class InlineRun(
    val text: String,
    val range: SourceRange,
    val strong: Boolean = false,
    val emphasis: Boolean = false,
    val code: Boolean = false,
    val link: Boolean = false,
    val linkUrl: String? = null,
)

internal sealed interface ChatMarkdownBlock {
    val range: SourceRange
}

internal data class InlineBlock(
    val runs: List<InlineRun>,
    override val range: SourceRange,
) : ChatMarkdownBlock

internal data class HeadingBlock(
    val level: Int,
    val runs: List<InlineRun>,
    override val range: SourceRange,
) : ChatMarkdownBlock

internal data class CodeBlock(
    val code: String,
    val language: String?,
    val runs: List<InlineRun>,
    override val range: SourceRange,
) : ChatMarkdownBlock

internal data class QuoteBlock(
    val blocks: List<ChatMarkdownBlock>,
    override val range: SourceRange,
) : ChatMarkdownBlock

internal data class ListItemBlock(
    val blocks: List<ChatMarkdownBlock>,
    val range: SourceRange,
)

internal data class ListBlock(
    val ordered: Boolean,
    val startNumber: Int,
    val items: List<ListItemBlock>,
    override val range: SourceRange,
) : ChatMarkdownBlock

internal data class RuleBlock(
    override val range: SourceRange,
) : ChatMarkdownBlock

internal data class ChatMarkdownDocument(
    val blocks: List<ChatMarkdownBlock>,
)

internal fun parseChatMarkdown(source: String): ChatMarkdownDocument {
    if (source.isEmpty()) return ChatMarkdownDocument(emptyList())
    val parser = Parser.builder()
        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
        .build()
    return ChatMarkdownDocument(renderBlocks(parser.parse(source), source))
}

private fun renderBlocks(parent: Node, source: String): List<ChatMarkdownBlock> {
    val result = ArrayList<ChatMarkdownBlock>()
    var child = parent.firstChild
    while (child != null) {
        renderBlock(child, source)?.let(result::add)
        child = child.next
    }
    return result
}

private fun renderBlock(node: Node, source: String): ChatMarkdownBlock? {
    val range = node.sourceRange(source)
    return when (node) {
        is Paragraph -> InlineBlock(renderInlines(node, source), range)
        is Heading -> HeadingBlock(node.level, renderInlines(node, source), range)
        is FencedCodeBlock -> CodeBlock(node.literal, node.info?.trim()?.takeIf { it.isNotEmpty() },
            codeRuns(node, node.literal, source, fenced = true), range)
        is IndentedCodeBlock -> CodeBlock(node.literal, null,
            codeRuns(node, node.literal, source, fenced = false), range)
        is BlockQuote -> QuoteBlock(renderBlocks(node, source), range)
        is BulletList -> ListBlock(
            ordered = false,
            startNumber = 1,
            items = renderListItems(node, source),
            range = range,
        )
        is OrderedList -> ListBlock(
            ordered = true,
            startNumber = node.markerStartNumber ?: 1,
            items = renderListItems(node, source),
            range = range,
        )
        is ThematicBreak -> RuleBlock(range)
        is HtmlBlock -> InlineBlock(
            listOf(InlineRun(node.literal, range)),
            range,
        )
        is Block -> InlineBlock(renderInlines(node, source), range)
        else -> null
    }
}

private fun renderListItems(node: Node, source: String): List<ListItemBlock> {
    val result = ArrayList<ListItemBlock>()
    var child = node.firstChild
    while (child != null) {
        if (child is ListItem) {
            result += ListItemBlock(renderBlocks(child, source), child.sourceRange(source))
        }
        child = child.next
    }
    return result
}

private fun renderInlines(parent: Node, source: String): List<InlineRun> {
    val result = ArrayList<InlineRun>()
    appendInlineRuns(parent.firstChild, source, InlineRunStyle(), parent.sourceRange(source), result)
    return result
}

private data class InlineRunStyle(
    val strong: Boolean = false,
    val emphasis: Boolean = false,
    val code: Boolean = false,
    val link: Boolean = false,
    val linkUrl: String? = null,
)

private fun appendInlineRuns(
    node: Node?,
    source: String,
    inherited: InlineRunStyle,
    fallback: SourceRange,
    output: MutableList<InlineRun>,
) {
    var current = node
    while (current != null) {
        val range = current.sourceRange(source).takeUnless { it.start == it.end } ?: fallback
        when (current) {
            is MarkdownText -> output += InlineRun(current.literal, range,
                inherited.strong, inherited.emphasis, inherited.code, inherited.link, inherited.linkUrl)
            is Code -> output += InlineRun(current.literal, range,
                inherited.strong, inherited.emphasis, code = true, inherited.link, inherited.linkUrl)
            is SoftLineBreak -> output += InlineRun(" ", range,
                inherited.strong, inherited.emphasis, inherited.code, inherited.link, inherited.linkUrl)
            is HardLineBreak -> output += InlineRun("\n", range,
                inherited.strong, inherited.emphasis, inherited.code, inherited.link, inherited.linkUrl)
            is HtmlInline -> output += InlineRun(current.literal, range,
                inherited.strong, inherited.emphasis, inherited.code, inherited.link, inherited.linkUrl)
            is Image -> {
                appendInlineRuns(current.firstChild, source, inherited, range, output)
            }
            is StrongEmphasis -> appendInlineRuns(
                current.firstChild, source, inherited.copy(strong = true), range, output)
            is Emphasis -> appendInlineRuns(
                current.firstChild, source, inherited.copy(emphasis = true), range, output)
            is Link -> appendInlineRuns(
                current.firstChild, source, inherited.copy(link = true, linkUrl = current.destination), range, output)
            else -> appendInlineRuns(current.firstChild, source, inherited, range, output)
        }
        current = current.next
    }
}

private fun Node.sourceRange(source: String): SourceRange {
    val spans = sourceSpans
    if (spans.isEmpty()) return SourceRange(0, source.length)
    val start = spans.minOf { it.inputIndex }
    val end = spans.maxOf { it.inputIndex + it.length }
    return SourceRange(start, end).normalized(source.length)
}

/** Match parser-produced code lines to their source spans, excluding fence and container markers. */
private fun codeRuns(node: Node, literal: String, source: String, fenced: Boolean): List<InlineRun> {
    val spans = if (fenced) node.sourceSpans.drop(1) else node.sourceSpans
    val result = ArrayList<InlineRun>()
    var position = 0
    var lineIndex = 0
    while (position < literal.length) {
        val newline = literal.indexOf('\n', position)
        val end = if (newline < 0) literal.length else newline
        val line = literal.substring(position, end)
        val span = spans.getOrNull(lineIndex)
        val range = if (span != null) {
            val spanEnd = (span.inputIndex + span.length).coerceAtMost(source.length)
            val spanStart = span.inputIndex.coerceAtMost(spanEnd)
            val raw = source.substring(spanStart, spanEnd)
            val start = if (raw.endsWith(line)) spanEnd - line.length else spanStart
            SourceRange(start, spanEnd)
        } else {
            val endOfBlock = node.sourceRange(source).end
            SourceRange(endOfBlock, endOfBlock)
        }
        result += InlineRun(line, range, code = true)
        if (newline >= 0) result += InlineRun("\n", SourceRange(range.end, range.end), code = true)
        position = if (newline < 0) literal.length else newline + 1
        lineIndex++
    }
    return result
}

@Composable
private fun ChatMarkdownBlock(
    block: ChatMarkdownBlock,
    source: String,
    frontier: Float,
    style: TextStyle,
    color: Color,
    onSaveCode: ((code: String, language: String?) -> Unit)?,
    key: String,
    indent: Int = 0,
) {
    when (block) {
        is InlineBlock -> MarkdownInlineText(
            runs = block.runs,
            source = source,
            frontier = frontier,
            style = style,
            color = color,
            modifier = Modifier.fillMaxWidth().padding(start = indent.dp),
        )
        is HeadingBlock -> {
            val headingStyle = when (block.level) {
                1 -> style.copy(fontSize = style.fontSize * 1.45f, fontWeight = FontWeight.Bold)
                2 -> style.copy(fontSize = style.fontSize * 1.25f, fontWeight = FontWeight.Bold)
                else -> style.copy(fontSize = style.fontSize * 1.1f, fontWeight = FontWeight.Bold)
            }
            MarkdownInlineText(block.runs, source, frontier, headingStyle, color,
                Modifier.fillMaxWidth().padding(start = indent.dp))
        }
        is CodeBlock -> MarkdownCodeBlock(
            block = block,
            source = source,
            frontier = frontier,
            color = color,
            onSaveCode = onSaveCode,
            modifier = Modifier.fillMaxWidth().padding(start = indent.dp),
        )
        is QuoteBlock -> Row(Modifier.fillMaxWidth().padding(start = indent.dp)) {
            Spacer(Modifier.width(3.dp).height(36.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = .50f),
                    RoundedCornerShape(2.dp)))
            Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.blocks.forEachIndexed { index, child ->
                    ChatMarkdownBlock(child, source, frontier, style, color, onSaveCode,
                        "$key-quote-$index", indent = 0)
                }
            }
        }
        is ListBlock -> Column(
            Modifier.fillMaxWidth().padding(start = indent.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            block.items.forEachIndexed { index, item ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        if (block.ordered) "${block.startNumber + index}." else "\u2022",
                        modifier = Modifier.padding(end = 8.dp),
                        style = style,
                        color = if (frontier >= item.range.start) color else Color.Transparent,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item.blocks.forEachIndexed { childIndex, child ->
                            ChatMarkdownBlock(child, source, frontier, style, color, onSaveCode,
                                "$key-item-$index-$childIndex", indent = 0)
                        }
                    }
                }
            }
        }
        is RuleBlock -> Spacer(
            Modifier.fillMaxWidth().height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)),
        )
    }
}

@Composable
private fun MarkdownInlineText(
    runs: List<InlineRun>,
    source: String,
    frontier: Float,
    style: TextStyle,
    color: Color,
    modifier: Modifier,
) {
    if (runs.isEmpty()) return
    val context = LocalContext.current
    val linkColor = MaterialTheme.colorScheme.primary
    // Only the link text itself carries a LinkAnnotation, so taps elsewhere in the paragraph and
    // long-press selection behave like normal text.
    val openLink = remember(context) {
        { url: String -> openMarkdownLink(context, url) }
    }
    val annotated = remember(runs, source, frontier, color, linkColor, openLink) {
        buildMarkdownAnnotatedString(runs, source, frontier, color, linkColor, openLink)
    }
    Text(annotated, modifier = modifier, style = style, color = color)
}

/** Allowed schemes for links rendered from model output. */
internal fun isSafeMarkdownLink(url: String?): Boolean {
    val scheme = url?.trim()?.substringBefore(':', "")?.lowercase() ?: return false
    return scheme == "http" || scheme == "https"
}

private fun openMarkdownLink(context: Context, url: String) {
    if (!isSafeMarkdownLink(url)) {
        Toast.makeText(context, "仅支持打开 http/https 链接", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (error: ActivityNotFoundException) {
        Toast.makeText(context, "没有可打开此链接的应用", Toast.LENGTH_SHORT).show()
    } catch (error: SecurityException) {
        Toast.makeText(context, "无法打开链接：${error.message ?: "权限不足"}", Toast.LENGTH_SHORT).show()
    }
}

internal fun buildMarkdownAnnotatedString(
    runs: List<InlineRun>,
    source: String,
    frontier: Float,
    color: Color,
    linkColor: Color,
    onLinkClick: ((String) -> Unit)? = null,
) = buildAnnotatedString {
    runs.forEach { run ->
        val start = length
        append(run.text)
        val end = length
        if (end <= start) return@forEach
        val span = SpanStyle(
            fontWeight = if (run.strong) FontWeight.Bold else null,
            fontStyle = if (run.emphasis) FontStyle.Italic else null,
            fontFamily = if (run.code) FontFamily.Monospace else null,
            background = if (run.code) color.copy(alpha = .10f) else Color.Unspecified,
            textDecoration = if (run.link) TextDecoration.Underline else null,
            color = if (run.link) linkColor else Color.Unspecified,
        )
        addStyle(span, start, end)
        val url = run.linkUrl?.trim()
        if (run.link && onLinkClick != null && isSafeMarkdownLink(url)) {
            addLink(
                LinkAnnotation.Url(url!!) { onLinkClick(url) },
                start,
                end,
            )
        }
        applyRevealAlpha(this, run.text, run.range, frontier, start, source.length,
            if (run.link) linkColor else color,
            background = if (run.code) color.copy(alpha = .10f) else Color.Unspecified)
    }
}

private fun applyRevealAlpha(
    builder: androidx.compose.ui.text.AnnotatedString.Builder,
    text: String,
    range: SourceRange,
    frontier: Float,
    outputStart: Int,
    sourceLength: Int,
    color: Color,
    background: Color,
) {
    if (text.isEmpty() || frontier >= range.end || frontier >= sourceLength) return
    val sourceStart = range.start.toFloat()
    val sourceEnd = range.end.coerceAtLeast(range.start + 1).toFloat()
    val localFrontier = ((frontier - sourceStart) / (sourceEnd - sourceStart) * text.length)
        .coerceIn(0f, text.length.toFloat())
    val reveal = streamRevealAnnotatedText(text, localFrontier, color)
    reveal.spanStyles.forEach { span ->
        val style = if (background == Color.Unspecified) span.item else span.item.copy(
            background = background.copy(alpha = background.alpha * (span.item.color.alpha / color.alpha)
                .let { if (it.isFinite()) it else 0f }),
        )
        builder.addStyle(style, outputStart + span.start, outputStart + span.end)
    }
}

@Composable
private fun MarkdownCodeBlock(
    block: CodeBlock,
    source: String,
    frontier: Float,
    color: Color,
    onSaveCode: ((code: String, language: String?) -> Unit)?,
    modifier: Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val languageLabel = block.language?.ifBlank { null } ?: "代码"
    GlassSurface(
        modifier = modifier,
        cornerRadius = 14.dp,
        tint = MaterialTheme.colorScheme.surface.copy(alpha = .30f),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(languageLabel, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = color.copy(alpha = .72f))
                DisableSelection { Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    MarkdownCodeAction("复制代码", "document") {
                        clipboard.setText(AnnotatedString(block.code))
                    }
                    if (onSaveCode != null) {
                        MarkdownCodeAction("保存代码", "download") {
                            onSaveCode(block.code, block.language)
                        }
                    }
                } }
            }
            val codeText = remember(block.runs, frontier, color, source.length) {
                buildMarkdownAnnotatedString(block.runs, source, frontier, color, color)
            }
            Text(
                codeText,
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = color,
            )
        }
    }
}

@Composable
private fun MarkdownCodeAction(
    label: String,
    glyph: String,
    onClick: () -> Unit,
) {
    val deformation = rememberGlassButtonDeformationState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        GlassSurface(
            modifier = Modifier.size(48.dp)
                .applyButtonDeformation(state = deformation, circularHit = true)
                .clip(RoundedCornerShape(24.dp)),
            cornerRadius = 24.dp,
            tint = MaterialTheme.colorScheme.surface.copy(alpha = .30f),
            buttonDeformation = deformation,
        ) {
            IconButton(
                onClick = onClick,
                modifier = Modifier.fillMaxSize().semantics { contentDescription = label },
            ) {
                FishGlyph(glyph, Modifier.size(17.dp), MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

