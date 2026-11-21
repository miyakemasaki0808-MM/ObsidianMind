package com.example.newproject.model.state

import com.example.newproject.model.RelatedNote

/**
 * 今のノートへのリンク（→ docs/dev/features/backlinks.md）。**ノート単位**で、ノートを替えたら捨てる。
 * 索引を作っている間の進み具合は Vault 単位の [KnowledgeIndexProgress] が持つ。
 */
sealed interface BacklinksState {
    /** 引いていない（ノートが無い・索引を持たない）。 */
    data object Idle : BacklinksState

    /** 引いている。走査がまだ公開されていない間もここで待ち、0件に畳まない。 */
    data object Loading : BacklinksState

    /** 引けた。0件なら節を出さない。 */
    data class Ready(val rows: List<BacklinkRow>) : BacklinksState

    /** 数えられなかった（索引が使えない・走査が読めずに空だった）。読書は止めない。 */
    data object Unavailable : BacklinksState
}

/**
 * 1ノート1行（→ backlinks.md §5）。[sentence] は本文で最初に出てくるリンクを含む1文、[otherCount] はほかの箇所の数。
 * 開くときは [sectionHeading] の、同じ見出しのうち [sectionHeadingOccurrence] 番目から。見出しより前なら冒頭から。
 */
data class BacklinkRow(
    val note: RelatedNote,
    val sentence: String,
    val otherCount: Int,
    val sectionHeading: String?,
    val sectionHeadingOccurrence: Int
)

/** 知識索引の進み具合（→ docs/dev/system/knowledge_index.md §5）。**Vault 単位**で、ノートを替えても消さない。 */
sealed interface KnowledgeIndexProgress {
    /** まだ分からない（索引を持たない・Vault を開いた直後）。 */
    data object Unknown : KnowledgeIndexProgress

    /** 最初の作成の途中。[indexed] は読んだノートの数、[total] は読む予定のノートの数。 */
    data class Building(val indexed: Int, val total: Int) : KnowledgeIndexProgress

    /** 最初の作成を終えた。その後の差分の読み直しは出さない。 */
    data object Ready : KnowledgeIndexProgress

    /**
     * 最初の作成が止まった（置き場を開けない・書けない・走査を頼めない）。「作っています」のまま残すと待てば出ると誤って伝えるので、
     * 節は数えられないと出す。次の正常な作成で [Building]・[Ready] へ戻る。作り終えた後の失敗ではここへ来ない。
     */
    data object Failed : KnowledgeIndexProgress
}
