package com.example.newproject.model

/**
 * 画像を出せなかった理由。**表示側が文言を選ぶための語彙**である。
 *
 * `Bitmap?` の null や Boolean にしないのは、6種類以上ある失敗を区別して
 * 伝えられないため（→ note_image_rendering §9）。「見つかりません」と
 * 「外部URLなので出せません」と「SVGは表示できません」は、
 * ユーザーが次に取る行動が違う。
 *
 * **解決の失敗と復号の失敗を1つの語彙にまとめている。** 分けると表示側が
 * 2つの型を突き合わせることになり、同じ意味の分岐が2箇所へ散る。
 * 葉である `model` に置くのは、`domain`（解決）・`data`（復号）・`ui`（表示）の
 * 3層が同じ語彙を使うため。
 */
sealed interface NoteImageFailure {

    /** 索引が完全で、そのうえで見つからなかった。**「Vaultに無い」と言ってよい。** */
    object NotFound : NoteImageFailure

    /**
     * 索引が不完全なので**「無い」と断定できない**。
     * 走査で読めなかったフォルダがあった、Vaultが未選択、走査自体が失敗した、のいずれか。
     */
    object Unverifiable : NoteImageFailure

    /** 同名のファイルが複数あり、どれを指すか決められない。 */
    data class Ambiguous(val candidateCount: Int) : NoteImageFailure

    /** 外部URL。**ネットワーク権限を持たないので構造的に取得できない。** */
    data class External(val url: String) : NoteImageFailure

    /** 参照先が空（`![]()` など）。 */
    object Empty : NoteImageFailure

    /** 形式が非対応（SVG など、`BitmapFactory` が扱えないもの）。 */
    object Unsupported : NoteImageFailure

    /** 上限を超えている（入力バイト・寸法のいずれか）。 */
    object TooLarge : NoteImageFailure

    /** ファイルは在るが読めない・壊れている。 */
    object Broken : NoteImageFailure
}
