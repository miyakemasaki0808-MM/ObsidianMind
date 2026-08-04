package com.example.newproject.domain.image

import com.example.newproject.domain.markdown.MarkdownBlock
import java.io.ByteArrayOutputStream

// ---------------------------------------------------------------------------
// 画像リンクの正規化と解決要求の組み立て。Android型を一切持たない純関数なので、
// 素のJVMユニットテストで検証できる。索引との照合は ImageIndexMatching が持つ。
// ---------------------------------------------------------------------------

/** 画像1つをどう解決すべきか。 */
internal sealed interface ImageRequest {

    /**
     * Vault内を探す。[vaultPath] は正規化済みのVaultルート相対パス、
     * [fileName] はその最終要素。**照合順は完全パス → ファイル名**（→ ImageIndexMatching）。
     */
    data class Lookup(val vaultPath: String, val fileName: String) : ImageRequest

    /**
     * 外部URL。**本アプリはネットワーク権限を持たないので構造的に取得できない。**
     * 「見つかりません」ではなく「外部URLなので出せない」と言うために型で分ける。
     */
    data class External(val url: String) : ImageRequest

    /** 参照先が空（`![]()` など）。 */
    object Empty : ImageRequest
}

/**
 * スキーム付きURL（`https://` `file://` など）と `data:` URI。
 *
 * `://` を要求するので、Windowsのドライブレター（`C:/...`）を誤検出しない。
 */
private val UrlSchemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")

/**
 * 画像ブロックから解決要求を作る。
 *
 * **サイズヒントを落とすのは埋め込みだけ。** `![[file|400]]` の `|` 以降はサイズだが、
 * リンク記法 `![alt|400](path)` では `|` が alt 側に付くので target には現れない。
 * 記法によって `|` の意味が違うため、一律に落とすと誤って対象を切る。
 */
internal fun imageRequestOf(block: MarkdownBlock.Image): ImageRequest {
    val raw = (if (block.isEmbed) block.target.substringBefore('|') else block.target).trim()
    if (raw.isEmpty()) return ImageRequest.Empty
    if (UrlSchemeRegex.containsMatchIn(raw) || raw.startsWith("data:")) {
        return ImageRequest.External(raw)
    }
    val path = normalizeVaultImagePath(raw)
    if (path.isEmpty()) return ImageRequest.Empty
    return ImageRequest.Lookup(vaultPath = path, fileName = path.substringAfterLast('/'))
}

/**
 * Vaultルート相対パスへ正規化する。**索引を作る側もこれを通す**
 * （片方だけ通すと、同じファイルが別のキーになって永久に一致しない）。
 *
 * 規則は5つ。
 * 1. パーセントエンコードを解く（Obsidianは空白を `%20` で書き出す）
 * 2. `\` を `/` へ寄せる
 * 3. 先頭 `/` と空要素（`//`）を捨てる
 * 4. `.` を捨て、`..` は1段上へ畳む
 * 5. **ルートを超える `..` は捨てる**（v1 はノート相対を解決しないので、
 *    基準は常にVaultルート。超えた分に意味を持たせようがない）
 *
 * **大文字小文字はここで畳まない。** 完全パス一致は区別し、ファイル名一致だけ
 * 区別しない仕様なので、畳むのは照合側の責務になる。
 */
internal fun normalizeVaultImagePath(raw: String): String {
    val decoded = percentDecode(raw).replace('\\', '/')
    val segments = mutableListOf<String>()
    for (segment in decoded.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
            else -> segments.add(segment)
        }
    }
    return segments.joinToString("/")
}

/**
 * `%XX` を解いてUTF-8として読み直す。
 *
 * `java.net.URLDecoder` を使わないのは、あれが `+` を空白へ変換するため
 * （クエリ文字列の規則であって、パスに当てると `a+b.png` が壊れる）。
 *
 * **非 `%` の区間はまとめて符号化する。** 1文字ずつ変換すると、
 * サロゲートペア（絵文字を含むファイル名）が2つの不正な単独サロゲートに割れる。
 * 不正なエスケープ（`%ZZ`・末尾の `%A`）はそのままの文字として残す。
 */
internal fun percentDecode(value: String): String {
    if ('%' !in value) return value
    val bytes = ByteArrayOutputStream(value.length)
    var literalStart = 0
    var index = 0
    while (index < value.length) {
        val high = if (value[index] == '%' && index + 2 < value.length) hexDigit(value[index + 1]) else -1
        val low = if (high >= 0) hexDigit(value[index + 2]) else -1
        if (low < 0) {
            index++
            continue
        }
        if (index > literalStart) {
            bytes.write(value.substring(literalStart, index).toByteArray(Charsets.UTF_8))
        }
        bytes.write(high * 16 + low)
        index += 3
        literalStart = index
    }
    if (literalStart < value.length) {
        bytes.write(value.substring(literalStart).toByteArray(Charsets.UTF_8))
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}
