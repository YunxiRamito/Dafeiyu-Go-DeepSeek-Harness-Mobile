package com.dafeiyu.mobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString

private fun effectiveColorAt(value: AnnotatedString, index: Int): Color =
    value.spanStyles.fold(Color.Black) { color, span ->
        if (index in span.start until span.end && span.item.color != Color.Unspecified) {
            span.item.color
        } else color
    }

private fun effectiveBackgroundAt(value: AnnotatedString, index: Int): Color =
    value.spanStyles.fold(Color.Transparent) { color, span ->
        if (index in span.start until span.end && span.item.background != Color.Unspecified) {
            span.item.background
        } else color
    }

fun main() {
    val firstDelta = "First streamed delta"
    check(initialStreamRevealFrontier(firstDelta, streaming = true, reducedMotion = false) == 0f)
    check(initialStreamRevealFrontier(firstDelta, streaming = false, reducedMotion = false) == firstDelta.length.toFloat())
    check(initialStreamRevealFrontier(firstDelta, streaming = true, reducedMotion = true) == firstDelta.length.toFloat())
    check(initialStreamRevealFrontier("", streaming = true, reducedMotion = false) == 0f)

    val markdown = "**AB** and `CD`"
    val block = parseChatMarkdown(markdown).blocks.single() as InlineBlock
    val expected = "AB and CD"
    val hidden = buildMarkdownAnnotatedString(block.runs, markdown, 0f, Color.Black, Color.Blue)
    check(hidden.text == expected)
    expected.indices.forEach { check(effectiveColorAt(hidden, it).alpha == 0f) }
    check(effectiveBackgroundAt(hidden, expected.indexOf('C')).alpha == 0f)

    val partial = buildMarkdownAnnotatedString(block.runs, markdown, 2.5f, Color.Black, Color.Blue)
    check(effectiveColorAt(partial, 0).alpha in .01f.. .99f)
    check(effectiveColorAt(partial, 1).alpha == 0f)
    check(partial.text == expected)
    val prefix = buildMarkdownAnnotatedString(block.runs, markdown, 4f, Color.Black, Color.Blue)
    check(effectiveColorAt(prefix, 0).alpha == 1f)
    check(effectiveColorAt(prefix, 1).alpha == 1f)
    check(effectiveColorAt(prefix, expected.indexOf('C')).alpha == 0f)

    val codeFrontier = markdown.indexOf('C') + .5f
    val codePartial = buildMarkdownAnnotatedString(block.runs, markdown, codeFrontier, Color.Black, Color.Blue)
    val codeIndex = expected.indexOf('C')
    check(effectiveColorAt(codePartial, codeIndex).alpha in .01f.. .99f)
    check(effectiveBackgroundAt(codePartial, codeIndex).alpha in .001f.. .099f)
    check(effectiveBackgroundAt(codePartial, codeIndex + 1).alpha == 0f)
    val final = buildMarkdownAnnotatedString(block.runs, markdown, markdown.length.toFloat(), Color.Black, Color.Blue)
    expected.indices.forEach { check(effectiveColorAt(final, it).alpha == 1f) }
    check(effectiveBackgroundAt(final, codeIndex) == Color.Black.copy(alpha = .10f))

    val fenced = "```txt\nABC\n```"
    val code = parseChatMarkdown(fenced).blocks.single() as CodeBlock
    val codeReveal = buildMarkdownAnnotatedString(code.runs, fenced, fenced.indexOf('A') + .5f,
        Color.Black, Color.Black)
    check(codeReveal.text == code.code && code.code == "ABC\n")
    check(effectiveColorAt(codeReveal, 0) == Color.Black.copy(alpha = .5f))
    check(effectiveColorAt(codeReveal, 1).alpha == 0f)

    val appended = "$markdown more"
    val appendedBlock = parseChatMarkdown(appended).blocks.single() as InlineBlock
    val settledFrontier = streamRevealFrontierAfterAppend(markdown, appended, markdown.length.toFloat())
    val next = buildMarkdownAnnotatedString(appendedBlock.runs, appended, settledFrontier, Color.Black, Color.Blue)
    expected.indices.forEach { check(effectiveColorAt(next, it).alpha == 1f) }
    check(effectiveColorAt(next, next.text.lastIndex).alpha == 0f)

    println("PASS: initial streaming/final/reduced frontier; Markdown left-to-right alpha; hidden/partial code backgrounds; fenced code; appended prefix stays visible; full selection text.")
}
