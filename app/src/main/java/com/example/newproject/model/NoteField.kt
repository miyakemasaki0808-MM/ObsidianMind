package com.example.newproject.model

/**
 * ノートの分野。**冊子の紙の地色が表すもの。**
 *
 * **一般語彙の6つだけが色を持つ**（→ `docs/dev/features/note_field_color.md` 判断13）。
 * Vault別の追加語彙は色を持たず、ラベルだけを細かくして色は親から継ぐので、
 * **この enum が増えることは色枠が増えることを意味する。**
 *
 * **「読書・記録」は入れない。** 読書は*形式*であって分野ではなく、枠にすると
 * 実測したVaultでは読書由来が6割を占めて**冊子がほぼ1色で埋まる**。
 * 本の内容であっても、技術書なら [Technical]、ビジネス書なら [Business] を選ぶ。
 *
 * @property label 画面に出す名前。**色以外の手がかり（WCAG 1.4.1）はこれが担う。**
 */
enum class NoteField(val label: String) {
    /** 手を動かす対象そのもの。コード・設計・環境。 */
    Technical("技術・開発"),

    /** 試験対策と、学習中の教材。 */
    Learning("学習・試験"),

    /** 業務・組織・手続き。 */
    Business("仕事・ビジネス"),

    /** 制作物、続けている関心事。 */
    Creative("創作・趣味"),

    /** 家事・買い物・健康・予定。 */
    Living("暮らし・生活"),

    /** 日記・振り返り・アイデアの断片・思索。 */
    Reflection("思考・内省")
}

/**
 * 1件のノートの分類。**3値で持つ**（→ `docs/dev/features/note_field_color.md` 判断8）。
 *
 * 2値（未判定／判定済み）にすると、**一時的なAI障害でヒントが永久に固定される。**
 * 表示できることと再判定が要ることを分けるために、暫定と確定を型で分ける。
 */
sealed interface NoteFieldClassification {

    /** 表示に使う分野。**null は無彩色**（未判定、または確定した「該当なし」）。 */
    val field: NoteField?

    /** まだ何も分かっていない。無彩色で出し、判定の対象にする。 */
    data object Unknown : NoteFieldClassification {
        override val field: NoteField? get() = null
    }

    /**
     * パスのヒントから置いた暫定。**色もラベルも出すが、再判定の対象として残る。**
     *
     * **永続しない**（→ 判断14）。Vault走査のたびに作り直せる導出値なので、
     * 永続層へ書くのは [Confirmed] だけである。
     */
    data class Provisional(override val field: NoteField) : NoteFieldClassification

    /**
     * AIが決めた確定。**同じ入力版のあいだは再判定しない。**
     *
     * @param field **null は「どの分野にも当たらない」という正常な結果**である（判断13 の `NONE`）。
     *   無彩色にするが、[Unknown] とは違って**再判定しない**。
     *   不正応答（空文字・未知のID・複数候補）はそもそもここへ来ない — 保存しないため。
     * @param inputVersion AIへ実際に渡した入力の指紋（→ 判断9）。
     *   本文・ヒント・語彙のどれかが変われば別の値になり、その時点でこの確定は有効でなくなる。
     */
    data class Confirmed(
        override val field: NoteField?,
        val inputVersion: String
    ) : NoteFieldClassification
}
