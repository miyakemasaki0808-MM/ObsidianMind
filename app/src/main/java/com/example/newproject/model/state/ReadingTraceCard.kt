package com.example.newproject.model.state

/**
 * 「前回のあなた」の再会カード。null のとき出さない。
 *
 * Rediscover で引いた時だけ組み立てる（検索・関連・直接オープンでは出さない）。
 * 由来を示すフラグを別に持たないのは、このフィールドを設定する経路が
 * `loadRandomNote` だけで、ノートを開くたびノート単位の状態リセットが
 * 消すため。二重の真実を作らない。
 *
 * [aiSummary] は俯瞰要約。生成前・生成失敗時は null で、その場合カードは
 * 生の痕跡（[lastSectionTitle] / [lastProgressPercent]）だけを見せる。
 */
data class ReadingTraceCard(
    val visitCount: Int,
    val lastVisitAtMillis: Long,
    val lastSectionTitle: String?,
    val lastProgressPercent: Int,
    val aiSummary: String? = null,
    val isSummaryLoading: Boolean = false,
    /**
     * 前回このノートに返事を残しているか。
     *
     * **中身はカードへ載せない。** 問い・返事・映し返しの3つを並べるとカードが重くなり、
     * 「前回のあなた」を1文で伝えるという役目が壊れる。ここでは
     * **在ることだけ**を示し、読むのは専用画面（→ features/reflect_remark.md §10.3）。
     */
    val hasReflectionReply: Boolean = false,
    /** 「読んだ」で畳んだ状態。永続化しないので次回 Rediscover では再表示される。 */
    val isDismissed: Boolean = false
)
