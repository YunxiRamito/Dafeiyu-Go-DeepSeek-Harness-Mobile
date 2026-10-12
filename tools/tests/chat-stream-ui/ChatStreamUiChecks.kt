package com.dafeiyu.mobile.ui

import androidx.compose.ui.graphics.Color
import com.dafeiyu.mobile.ui.components.GlassMotion
import com.dafeiyu.mobile.ui.components.streamCharacterBoundaries
import com.dafeiyu.mobile.ui.components.streamRevealAnnotatedText
import com.dafeiyu.mobile.ui.components.streamRevealFrontierAfterAppend

fun main() {
    val sentences = listOf(
        "\u5148\u6838\u5bf9\u3002\u73b0\u5728\u68c0\u67e5\u63a5\u53e3\u3002" to
            "\u73b0\u5728\u68c0\u67e5\u63a5\u53e3\u3002",
        "\u5148\u6838\u5bf9\u3002\u73b0\u5728\u68c0\u67e5\u63a5\u53e3" to
            "\u73b0\u5728\u68c0\u67e5\u63a5\u53e3",
        "\u7b2c\u4e00\u884c\n\u5f53\u524d\u4e00\u53e5\u3002" to
            "\u5f53\u524d\u4e00\u53e5\u3002",
        "Check first. Check version 0.2.0." to "Check version 0.2.0.",
        "\u5148\u6838\u5bf9\u3002\u300c\u73b0\u5728\u68c0\u67e5\u63a5\u53e3\u3002\u300d" to
            "\u300c\u73b0\u5728\u68c0\u67e5\u63a5\u53e3\u3002\u300d",
        "\u5148\u6838\u5bf9\u3002\u300d \u5f53\u524d\u4e00\u53e5\u3002" to
            "\u5f53\u524d\u4e00\u53e5\u3002",
        "   " to "",
    )
    sentences.forEach { (source, expected) ->
        val actual = latestPublicThinkingSentence(source)
        check(actual == expected) { "Latest public sentence: expected <$expected>, got <$actual>" }
    }
    val original = "\u4e2d\u6587 **Markdown**\n```kotlin\nprintln(\"ok\")\n```"
    listOf(0f, .5f, 2f, 9.5f, original.length.toFloat()).forEach { frontier ->
        val value = streamRevealAnnotatedText(original, frontier, Color.Black)
        check(value.text == original) { "Layout/selection text changed" }
        if (frontier >= original.length) check(value.spanStyles.isEmpty())
        else {
            check(value.spanStyles.first().start == frontier.toInt())
            check(value.spanStyles.first().item.color.alpha in 0f..1f)
            check(value.spanStyles.last().end == original.length)
        }
    }
    val before = streamRevealAnnotatedText("Already read.", 13f, Color.Black)
    val appended = streamRevealAnnotatedText("Already read. New.", 13.5f, Color.Black)
    check(before.spanStyles.isEmpty())
    check(appended.spanStyles.all { it.start >= 13 }) { "Earlier text was replayed" }
    val unicode = "A\uD83D\uDE00e\u0301\u4e2d"
    val boundaries = streamCharacterBoundaries(unicode)
    check(2 !in boundaries && 4 !in boundaries) { "Split surrogate or combining sequence" }
    val value = streamRevealAnnotatedText(unicode, 2f, Color.Black)
    check(value.spanStyles.first().start == 1 && value.spanStyles.first().end == 3)
    check(value.text == unicode)
    val composed = "e\u0301X"
    val cursor = streamRevealFrontierAfterAppend("e", composed, 1f)
    check(cursor == 2f)
    check(streamRevealAnnotatedText(composed, cursor, Color.Black).spanStyles.all { it.start >= 2 })
    check(streamRevealFrontierAfterAppend("longer original", "replacement", 3f) == "replacement".length.toFloat())
    check(streamRevealFrontierAfterAppend("hello", "hello world", 5f) == 5f)
    check(streamRevealAnnotatedText("", 0f, Color.Black).spanStyles.isEmpty())
    check(GlassMotion.scalar(GlassMotion.MorphMillis).durationMillis == 380)
    check(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion = true).durationMillis == 0)
    println("PASS: 7 source-sentence cases; full layout/selection text; append-only spans; Unicode/combining deltas; replacement; final and empty text; motion 380ms/reduced 0ms.")
}
