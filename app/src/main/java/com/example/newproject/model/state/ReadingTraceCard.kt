package com.example.newproject.model.state

import com.example.newproject.model.ReunionKind

/**
 * 「前回のあなた」の再会カード。null のとき出さない。
 *
 * Rediscover で引いた時だけ組み立てる（検索・関連・直接オープンでは出さない）。
 * 由来を示すフラグを別に持たないのは、このフィールドを設定する経路が
 * `loadRandomNote` だけで、ノートを開くたびノート単位の状態リセットが
 * 消すため。二重の真実を作らない。
 *
 * **枠に何を出すかはこの型だけでは決まらない。** 最後まで読んだノートの既定はノートの要約で、
 * それは `SummaryState` にある。両方から決める純関数は `domain.reunionSlot`
 * （→ features/reunion_card.md 判断6「枠の中身は1つの純関数が決める」）。
 */
data class ReadingTraceCard(
    val visitCount: Int,
    val lastVisitAtMillis: Long,
    val lastSectionTitle: String?,
    val lastProgressPercent: Int,
    /**
     * この Controller が枠へ出す1件 — 当時の問い・古い前提・前後の要約。
     * **ノートの要約はここへ入れない**（`SummaryState` から読む）。
     */
    val aiSummary: String? = null,
    /**
     * [aiSummary] がどの種別か。**前置きの文言をここから決める。**
     *
     * 文言だけで見分けさせないのが要点で、種別が生成文の中にしか無いと
     * 表示側が条件分岐できず検査も書けない（→ features/reunion_card.md 判断2）。
     */
    val aiSummaryKind: ReunionKind? = null,
    /**
     * このノートに余白メモが残っているか。
     *
     * **中身はカードへ載せない。** 並べるとカードが重くなり、「前回のあなた」を
     * 1文で伝えるという役目が壊れる。ここでは**在ることだけ**を示し、読むのはシート。
     */
    val hasMemos: Boolean = false,
    /** この Controller が枠の中身（前後の要約・問いの選別）を作っている最中。ノートの要約の進行は含まない。 */
    val isSummaryLoading: Boolean = false,
    /**
     * 続きから読むの送り先（本文のブロック番号）。**途中まで読んだノートで、読み進めたところが求まったときだけ。**
     * 番号は到達率を測っているのと同じ並び（`NoteSectionModel.blocks`）で数える。
     */
    val resumeBlockIndex: Int? = null,
    /** 「読んだ」で畳んだ状態。永続化しないので次回 Rediscover では再表示される。 */
    val isDismissed: Boolean = false
)
