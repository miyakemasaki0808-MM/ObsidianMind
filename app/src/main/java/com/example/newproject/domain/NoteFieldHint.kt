package com.example.newproject.domain

import com.example.newproject.model.NoteField

/**
 * Vault内の相対パスから、分野の**ヒント**を作る。
 *
 * **ヒントは判定ではない**（→ `docs/dev/features/note_field_color.md` 判断6）。
 * AIには候補として添えるだけで、AIは常に自分で判断する。
 * フォルダで決めてしまうと、**採番体系や命名が崩れて*間違った*分野を返すようになっても
 * AIが呼ばれない**ため、崩れたときに効かなくなる。
 *
 * ## なぜ辞書照合か
 *
 * フォルダ名をそのまま分野名にする案は、**実測で成立しなかった** — オーナーのVault（645本）では
 * 24区分に割れ、区分名が採番コードで意味を読めなかった（→ 判断4 の表）。
 * **一般語彙の辞書と突き合わせる**なら、Nano も追加I/Oも要らず、
 * 第三者のVaultにも同じ規則で当たる（→ 判断12）。
 *
 * **当たらないことは失敗ではない。** 採番コードだけのフォルダ名やフラットなVaultでは
 * ヒントが作れず null を返す。そのときAIは本文だけで判断する。
 *
 * ## 当たり方の規則
 *
 * **パスの浅い側が勝つ。** 上位のフォルダほど大きな文脈を表すため、
 * `試験対策/Python入門/…` は [NoteField.Learning] であって [NoteField.Technical] ではない
 * （試験のために読んでいる教材だから）。**同じ深さで複数当たったら [NoteField] の宣言順**で決める —
 * 規則を1つに決めておかないと、辞書へ語を足すたびに既存ノートの色が入れ替わる。
 *
 * @param vaultRelativePath 走査時に組み立て済みの相対パス。ファイル名まで含んでよい。
 * @return 当たった分野。当たらなければ null。
 */
fun noteFieldHint(vaultRelativePath: String): NoteField? {
    if (vaultRelativePath.isBlank()) return null
    // 浅い側から見て、最初に当たった深さで打ち切る。
    // **ファイル名（最後の要素）も対象にする** — フォルダを持たないVaultでも当たるようにするため。
    for (segment in vaultRelativePath.split('/')) {
        if (segment.isBlank()) continue
        val tokens = TOKEN_SEPARATOR.split(segment.lowercase()).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) continue
        // 同じ深さで複数当たりうるので、宣言順で最初のものを採る。
        val matched = NoteField.entries.firstOrNull { field ->
            HINT_KEYWORDS.getValue(field).any { keyword -> tokens.any { keyword.matches(it) } }
        }
        if (matched != null) return matched
    }
    return null
}

/**
 * 手がかり語1つ。**照合の仕方が字種で変わる。**
 *
 * - **英字は完全一致。** 部分一致にすると `art` が `article` に当たるような取り違えが起きる
 *   （実際にオーナーのVaultで206本が誤った分野のヒントを持った）。活用形が要るなら語を足す。
 * - **日本語は部分一致。** 語の切れ目が無いので、`試験クリア` の中の `試験` を拾うには
 *   トークンの内側を見るしかない。
 */
private class HintKeyword(private val value: String) {
    private val isAscii = value.all { it.code < 128 }

    fun matches(token: String): Boolean = if (isAscii) token == value else token.contains(value)
}

private fun ascii(vararg values: String) = values.map(::HintKeyword)

/** 英数字以外で区切る。`0F01_Technical_Detail` → `0f01` / `technical` / `detail`。 */
private val TOKEN_SEPARATOR = Regex("""[^\p{L}\p{N}]+""")

/**
 * 分野ごとの手がかり語。
 *
 * **一般的すぎる語を入れない。** 「note」「data」「file」「project」のような語は
 * どのVaultにも現れるので、入れた瞬間にほぼ全ノートが同じ分野のヒントを持ち、
 * **ヒントが情報を運ばなくなる。** 迷ったら入れない — 当たらなければAIが本文で判断するだけで、
 * **誤ったヒントを添えるほうが害が大きい。**
 *
 * **入れてから外した語がある。** 実測（オーナーのVault 645本）で誤りが見えたもの —
 * `デザイン`/`design` は*技術*にも*創作*にも読めて（「デザインパターン」は技術書）、
 * `思考` は書名によく現れて内省と関係しない（「システム思考」）。
 * **どちらも分野をまたぐ語**で、辞書照合では正しい側を選べない。**AIの仕事として残す。**
 *
 * 日本語と英語を並べるのは、Obsidian のフォルダ名がどちらにもなり得るため。
 */
private val HINT_KEYWORDS: Map<NoteField, List<HintKeyword>> = mapOf(
    NoteField.Technical to ascii(
        "技術", "開発", "設計", "実装", "プログラム", "コード",
        "technical", "tech", "develop", "development", "dev", "engineering",
        "architecture", "programming", "coding", "code"
    ),
    NoteField.Learning to ascii(
        "学習", "勉強", "試験", "資格", "講座", "教材",
        "learning", "study", "exam", "exams", "license", "certification", "course", "tutorial"
    ),
    NoteField.Business to ascii(
        "仕事", "業務", "会議", "議事", "顧客", "営業", "ビジネス",
        "business", "meeting", "meetings", "client", "clients", "sales", "invoice"
    ),
    NoteField.Creative to ascii(
        "創作", "趣味", "制作", "音楽", "写真", "小説", "ゲーム",
        "creative", "hobby", "music", "photo", "photos", "novel", "art", "arts"
    ),
    NoteField.Living to ascii(
        "暮らし", "生活", "家事", "料理", "献立", "健康", "買い物", "旅行",
        "living", "cooking", "recipe", "recipes", "health", "shopping", "travel"
    ),
    NoteField.Reflection to ascii(
        "日記", "日誌", "振り返り", "内省", "アイデア", "着想",
        "diary", "journal", "retrospective", "reflection", "idea", "ideas"
    )
)
