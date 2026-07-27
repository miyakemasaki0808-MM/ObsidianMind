package com.example.newproject.model

/**
 * AIプロンプトへ渡すために準備済みの本文抜粋。
 *
 * 生の [String] を誤って直接プロンプトへ渡す経路をコンパイルで防ぐための型であり、
 * 同一Gradleモジュール内からの生成そのものを禁止する境界ではない。生成は原則として
 * `domain` の `buildNoteExcerpt` に集約する。
 */
class NoteExcerpt internal constructor(
    val text: String,
    val isAbridged: Boolean
)
