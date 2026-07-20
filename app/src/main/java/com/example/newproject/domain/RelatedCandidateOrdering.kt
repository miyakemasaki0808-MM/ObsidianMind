package com.example.newproject.domain

import com.example.newproject.toNormalizedObsidianTitle

// 関連ノートAI候補の並べ替え・除外・上限適用（純ロジック）。
// android.net.Uri に依存しないため素のJVMユニットテストで検証できる。
// Uri同一性でのdedup（AI応答→NoteFile解決）はUseCase側に残す。

// 先頭4桁の16進プレフィックス（例: 0F01）を大文字で返す。該当しなければ null。
internal fun extractHexPrefix(title: String): String? =
    Regex("^([0-9A-Fa-f]{4})").find(title)?.groupValues?.get(1)?.uppercase()

/**
 * AIへ渡す候補を、除外・並べ替え・上限適用して返す純ロジック（要素型は任意）。
 *
 * - [titleOf] でタイトルを取り出すため、NoteFile など Uri を持つ型でも識別子を保てる。
 *   同名・別Uriは別要素のまま維持され、IDはこの並び順に対して採番できる。
 * - [excludedTitles]（決定的チャンネルに出したタイトル）を**上限適用の前に**落とす。
 *   これによりAIチャンネルを「未表示ノートの補完」に純化し、上限分の枠を無駄にしない。
 * - 現ノートに採番があるときは 上位2桁一致（兄弟）→ 上位1桁一致（大カテゴリ）→ 採番なし の順。
 *   採番が無いときは入力順のまま上限だけ適用する。
 * - 上限は本メソッドが唯一の制限箇所（プロンプト側では切らない）。
 */
internal fun <T> orderRelatedCandidates(
    currentTitle: String,
    candidates: List<T>,
    titleOf: (T) -> String,
    excludedTitles: Set<String>,
    limit: Int
): List<T> {
    val excluded = excludedTitles.map { it.toNormalizedObsidianTitle() }.toSet()
    val pool = candidates.filterNot { titleOf(it).toNormalizedObsidianTitle() in excluded }

    val currentPrefix = extractHexPrefix(currentTitle) ?: return pool.take(limit)
    val twoDigit = currentPrefix.take(2)
    val oneDigit = currentPrefix.take(1)

    val sameGroup = pool.filter { extractHexPrefix(titleOf(it))?.take(2) == twoDigit }
    val sameCategory = pool.filter {
        val p = extractHexPrefix(titleOf(it)) ?: return@filter false
        p.take(1) == oneDigit && p.take(2) != twoDigit
    }
    val noPrefix = pool.filter { extractHexPrefix(titleOf(it)) == null }

    return (sameGroup + sameCategory + noPrefix).take(limit)
}

// タイトル文字列のみ扱う経路向けの薄いラッパ。
internal fun orderRelatedCandidateTitles(
    currentTitle: String,
    candidateTitles: List<String>,
    excludedTitles: Set<String>,
    limit: Int
): List<String> =
    orderRelatedCandidates(currentTitle, candidateTitles, { it }, excludedTitles, limit)
