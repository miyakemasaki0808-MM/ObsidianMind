package com.example.newproject.domain

// 関連ノートAI候補の一時ID（C01..）の採番と、応答からのID抽出（純ロジック・Uri非依存）。
// タイトルではなくIDを返させることで、言い換え・翻訳・装飾・同名衝突をまとめて解消する。

// 0始まりのインデックスを C01, C02, … の2桁ゼロ埋めIDへ。
internal fun relatedCandidateId(index: Int): String =
    "C" + (index + 1).toString().padStart(2, '0')

// 行頭のIDのみを対象にする。C の後は1〜2桁で、直後に数字が続かないこと
// （"C012" のようなタイトル断片を弾く）。桁落ち "C5" は後段でゼロ埋め補正する。
private val CANDIDATE_ID_PATTERN = Regex("^C(\\d{1,2})(?![0-9])", RegexOption.IGNORE_CASE)

/**
 * モデル応答から候補IDを抽出する。
 *
 * - 箇条書き記号・連番・コードフェンス・引用符を剥がしたうえで、**行頭付近**のIDのみ拾う
 *   （説明文中に紛れたIDは拾わない）。
 * - [validIds] に無いID（未知・候補外）は破棄。大文字小文字と桁落ちは吸収する。
 * - 同一IDは1件に畳み、出現順を保って最大 [limit] 件返す。
 */
internal fun parseCandidateIds(
    response: String,
    validIds: Set<String>,
    limit: Int
): List<String> {
    if (limit <= 0) return emptyList()
    val seen = LinkedHashSet<String>()
    for (raw in response.lineSequence()) {
        var line = raw.trim()
        line = line.removePrefix("-").removePrefix("*").removePrefix("•").trim()
        line = line.replace(Regex("^\\d+[.)]\\s*"), "")
        line = line.trim('`', '"', '\'', ' ')

        val match = CANDIDATE_ID_PATTERN.find(line) ?: continue
        val id = "C" + match.groupValues[1].padStart(2, '0')
        if (id in validIds && seen.add(id) && seen.size >= limit) break
    }
    return seen.toList()
}
