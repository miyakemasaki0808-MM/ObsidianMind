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
     * **いまの用途は関連ノートの再ランクだけ。** かつては廃止した「ノートへのひとこと」へ
     * 候補として渡していたが、その受け側はもう無い。
     * **消さないのは、再ランクが既に読んだ値をそのまま持っているため**で、
     * 新しい読み手を足すなら追加のI/Oが無いこの値から考える。
     */
    val snippet: String? = null,
    /**
     * 近いと見た根拠。**AI推薦の経路でだけ入り**、再ランクが既に計算した値から取るので追加の計算も生成も無い。
     * 決定的な関連ノートは「linked」や採番で関連が見えるので null。弱い根拠しか無ければ null。
     */
    val reason: RelatedReason? = null
)
