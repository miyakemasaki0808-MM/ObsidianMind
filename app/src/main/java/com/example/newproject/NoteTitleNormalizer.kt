package com.example.newproject

internal fun String.toObsidianNoteTitle(): String =
    trim()
        .substringBefore("|")
        .substringBefore("#")
        .substringBefore("^")
        .trim()
        .substringAfterLast("/")
        .substringAfterLast("\\")
        .removeMarkdownExtension()
        .trim()

internal fun String.toNormalizedObsidianTitle(): String =
    toObsidianNoteTitle().lowercase()

private fun String.removeMarkdownExtension(): String =
    if (endsWith(".md", ignoreCase = true)) dropLast(3) else this
