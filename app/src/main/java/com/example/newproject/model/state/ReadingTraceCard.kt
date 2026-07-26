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
    /** 「読んだ」で畳んだ状態。永続化しないので次回 Rediscover では再表示される。 */
    val isDismissed: Boolean = false
)
