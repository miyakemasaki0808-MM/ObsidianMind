package com.example.newproject.model

/**
 * 前回いちばん先まで読んだところを境にした、本文の前後（→ features/reunion_card.md 判断6）。
 *
 * **境目を持ったまま渡す。** 前後をつないだ1本の文字列にすると、どこまで読んだかが
 * プロンプトから消え、「直前に読んでいたこと」と「この先にあること」を書き分けられない。
 *
 * 切り出しは `domain` の純関数が行い、`ai` は整形だけをする（→ architecture.md 判断6）。
 */
data class ReunionPassage(
    /** 読み進めたブロックまで（そのブロックを含む）。 */
    val before: String,
    /** その次のブロックから。読み進めたブロックが末尾なら空。 */
    val after: String
)
