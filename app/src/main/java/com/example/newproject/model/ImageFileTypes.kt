package com.example.newproject.model

/**
 * 画像として扱うファイル拡張子。
 *
 * **「認識できる」と「復号できる」は別物**で、ここは前者の一覧である。
 * `svg` は `BitmapFactory` が扱えないが、**画像として認識しないと
 * 「見つかりません」という誤った理由が出る**（正しくは「SVGは非対応」）。
 * `heic` / `heif` / `avif` も端末とAPIレベル次第で復号に失敗するが、
 * 失敗の理由を出すのは復号側の責務であって、認識側で落とすと理由がすり替わる。
 *
 * 索引側（走査で集めるファイル）もこの一覧を使う。**認識と索引がずれると、
 * 認識はできるのに索引に無いファイルが「不在」として扱われる**ため、
 * 2つの一覧に分けない。
 */
internal val IMAGE_FILE_EXTENSIONS: Set<String> = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif"
)

/**
 * ファイル名・パスの拡張子が画像かどうか。
 *
 * **wiki埋め込み `![[...]]` を画像に限定するために要る。** 拡張子を見ないと
 * `![[note]]`（他ノートの埋め込み＝別機能）まで画像として解決しにいくことになる。
 *
 * 呼び出し側は**サイズヒント（`img.png|400`）を落としてから**渡すこと。
 * ここで落とさないのは、落とす位置が記法によって違うため
 * （埋め込みは `|` の前がファイル名、リンクは後ろが表示名）。
 */
internal fun isImageFileName(name: String): Boolean =
    name.substringAfterLast('.', missingDelimiterValue = "").lowercase() in IMAGE_FILE_EXTENSIONS
