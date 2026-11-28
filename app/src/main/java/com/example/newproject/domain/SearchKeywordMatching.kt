package com.example.newproject.domain

import com.example.newproject.model.NoteFile
// ---------------------------------------------------------------------------
// さがすタブのキーワード一致。
//
// 文字bigramの重なり数で採点する（日本語トークナイザ不要・再現率重視）。用途は2つあり、
// 求めるものが違う。
//
// - 再現率カット（[recallCutByKeyword]）: 候補が多すぎて Nano へ渡せないときの粗い絞り込み。
//   精度は Nano が担保するので、一致0件の候補も残して取りこぼしを避ける。
// - フォールバック（[pickByKeyword]）: AIが使えない/空振りしたときに、そのまま画面へ出す
//   検索結果。UIが「ことばの一致で出しています」と言う以上、一致0件は返さない。
//
// Uri を持つ NoteFile ではなくタイトル抽出関数を受け取る形にしてあるので、
// Android を起動せず素のJVMテストで検証できる。
// ---------------------------------------------------------------------------

/**
 * 照らす言葉。正規化と bigram をここで1回だけ作る。並べるときは候補の数だけ採点するので、
 * 採点のたびに言葉の bigram を作り直さないよう、作ったものを受け取る形にしてある。
 */
internal class KeywordQuery(query: String) {
    val normalized: String = query.normalizedForMatching()
    val bigrams: Set<String> = normalized.bigrams()
}

/** [query] と [title] の一致度。0 は「1文字も重なっていない」。 */
internal fun keywordMatchScore(query: String, title: String): Int = keywordMatchScore(KeywordQuery(query), title)

/** 1文字の言葉は bigram が作れず全件0点になってしまうため、部分一致で見る。 */
internal fun keywordMatchScore(query: KeywordQuery, title: String): Int {
    if (query.normalized.length == 1) {
        return if (title.normalizedForMatching().contains(query.normalized)) 1 else 0
    }
    return title.bigrams().count { it in query.bigrams }
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
 * スコアは事前に1回だけ計算する — sortedByDescending のセレクタは比較のたびに呼ばれる。
 * 言葉の bigram も [KeywordQuery] で1回だけ作る。sortedByDescending は安定ソートなので、同点は元の並び順を保つ。
 */
private fun <T> rankByKeyword(
    query: String,
    items: List<T>,
    title: (T) -> String
): List<Pair<T, Int>> {
    val key = KeywordQuery(query)
    if (key.normalized.isEmpty()) return items.map { it to 0 }
    return items
        .map { item -> item to keywordMatchScore(key, title(item)) }
        .sortedByDescending { it.second }
}

/** 大文字小文字と空白の違いを無視するための形。タイトルの照合と共有する。 */
internal fun String.normalizedForMatching(): String = lowercase().filterNot { it.isWhitespace() }

/**
 * 照らすタイトル。ファイル名から拡張子を除く。照らすと `m`・`d`・`md` が全ノートに当たる。
 * 走査は大文字の `.MD` も拾うので、大文字小文字を問わず除く。
 */
internal fun titleForMatching(fileName: String): String =
    if (fileName.endsWith(".md", ignoreCase = true)) fileName.dropLast(3) else fileName

private fun String.bigrams(): Set<String> {
    val cleaned = normalizedForMatching()
    if (cleaned.length < 2) return emptySet()
    return (0 until cleaned.length - 1).map { cleaned.substring(it, it + 2) }.toSet()
}
