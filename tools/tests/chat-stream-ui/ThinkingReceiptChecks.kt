package com.dafeiyu.mobile.ui

fun main() {
    val sources = listOf(
        "Earlier. Latest   public sentence." to "Latest   public sentence.",
        "Earlier.\nLatest\tpublic sentence." to "Latest\tpublic sentence.",
        "Earlier. Current version 0.1.19." to "Current version 0.1.19.",
        "Earlier.\nCurrent partial" to "Current partial",
        "Earlier.\n\"Current sentence.\"" to "\"Current sentence.\"",
    )
    sources.forEach { (source, expected) ->
        check(latestPublicThinkingSentence(source) == expected)
    }
    check(latestPublicThinkingSentence("Earlier. " + "x".repeat(200_000)) == "x".repeat(512) + "\u2026")
    check(latestPublicThinkingSentence("x".repeat(511) + "\uD83D\uDE00tail") ==
        "x".repeat(511) + "\u2026")
    check(latestPublicThinkingSentence("Current public sentence.", 7) == "Current\u2026")
    check(latestPublicThinkingSentence(" ").isEmpty())
    println("PASS: 9 thinking receipt cases; public source whitespace retained; partial/version/quotes; " +
        "512-character title bound; safe surrogate clipping; no full large-sentence copy.")
}
