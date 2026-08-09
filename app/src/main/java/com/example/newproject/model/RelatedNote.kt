package com.example.newproject.model

data class RelatedNote(
    val title: String,
    val ref: DocumentRef,
    val isWikilinked: Boolean,
    val lastModified: Long? = null,
    /**
     * 本文冒頭のスニペット。**AI推薦の経路でだけ入る。**
     *
     * 関連ノートAIは再ランク（Phase 3b）のために候補の本文を既に読んでおり、
     * これはその値を1段上へ通しているだけなので**追加のI/Oは発生しない**。
     * 決定的な関連ノート（wikilink・同採番グループ）は本文を読まないので null。
     *
     * 用途は「ノートへのひとこと」の候補提示 — タイトルだけでは
     * 中身に踏み込んだ接続理由を作れないため。
     */
    val snippet: String? = null
)

enum class AiRecommendationStatus {
    Ready,
    Unavailable,
    NeedsDownload,
    Error
}
