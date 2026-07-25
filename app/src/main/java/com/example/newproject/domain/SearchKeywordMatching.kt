package com.example.newproject.domain

// ---------------------------------------------------------------------------
// さがすタブのキーワード一致。
//
// 文字bigramの重なり数で採点する（日本語トークナイザ不要・再現率重視）。用途は2つあり、
// 求めるものが違う。
//
// - 再現率カット（[recallCutByKeyword]）: 候補が多すぎて Nano へ渡せないときの粗い絞り込み。
//   精度は Nano が担保するので、一致0件の候補も残して取りこぼしを避ける。
// - フォールバック（[pickByKeyword]）: AIが使えない/空振りしたときに、そのまま画面へ出す
//   検索結果。UIが「キーワード一致で表示しています」と言う以上、一致0件は返さない。
//
// Uri を持つ NoteFile ではなくタイトル抽出関数を受け取る形にしてあるので、
// Android を起動せず素のJVMテストで検証できる。
// ---------------------------------------------------------------------------

/**
 * [query] と [title] の一致度。0 は「1文字も重なっていない」。
 *
 * 1文字クエリは bigram が作れず全件0点になってしまうため、部分一致で見る。
 */
internal fun keywordMatchScore(query: String, title: String): Int {
    val cleanedQuery = query.normalizedForMatching()
    if (cleanedQuery.isEmpty()) return 0
    if (cleanedQuery.length == 1) {
        return if (title.normalizedForMatching().contains(cleanedQuery)) 1 else 0
    }
    val queryBigrams = cleanedQuery.bigrams()
    return title.bigrams().count { it in queryBigrams }
}

/**
 * 一致度の高い順に並べ、上位 [limit] 件を返す。**一致0件は落とす**ので、
 * 何も一致しなければ空リストになる（＝「見つかりませんでした」）。
 */
internal fun <T> pickByKeyword(
    query: String,
    items: List<T>,
    limit: Int,
    title: (T) -> String
): List<T> = rankByKeyword(query, items, title)
    .filter { it.second > 0 }
    .take(limit)
    .map { it.first }

/**
 * 一致度の高い順に上位 [limit] 件へ粗く絞る。こちらは**一致0件も残す**
 * （最終的な精度は Nano が担保するため、取りこぼさないことを優先する）。
 */
internal fun <T> recallCutByKeyword(
    query: String,
    items: List<T>,
    limit: Int,
    title: (T) -> String
): List<T> = rankByKeyword(query, items, title).take(limit).map { it.first }

/**
 * スコアを添えて降順に並べる。
 *
 * スコアは事前に1回だけ計算する。sortedByDescending のセレクタは比較のたびに
 * 呼ばれるため、以前は bigram 集合の構築が O(n log n) 回走っていた（P4）。
 * sortedByDescending は安定ソートなので、同点は元の並び順を保つ。
 */
private fun <T> rankByKeyword(
    query: String,
    items: List<T>,
    title: (T) -> String
): List<Pair<T, Int>> {
    val cleanedQuery = query.normalizedForMatching()
    if (cleanedQuery.isEmpty()) return items.map { it to 0 }
    return items
        .map { item -> item to keywordMatchScore(cleanedQuery, title(item)) }
        .sortedByDescending { it.second }
}

private fun String.normalizedForMatching(): String = lowercase().filterNot { it.isWhitespace() }

private fun String.bigrams(): Set<String> {
    val cleaned = normalizedForMatching()
    if (cleaned.length < 2) return emptySet()
    return (0 until cleaned.length - 1).map { cleaned.substring(it, it + 2) }.toSet()
}
