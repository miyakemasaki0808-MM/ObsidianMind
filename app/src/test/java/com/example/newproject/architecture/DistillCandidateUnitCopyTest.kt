package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蒸留の画面文言が、候補の単位を「文」と決めつけていないことを固定する。
 *
 * ## なぜ要るか
 *
 * 蒸留の候補は文・句・語句が混ざる。**それでも画面は「N文を太字にします」と説明していた。**
 * ここはアプリで唯一ノート本文を書き換える確定境界なので、
 * 単位がずれると**変更範囲そのものを誤認させる。**
 *
 * 2026-08-17、件数の3箇所を「箇所」へ直した同じ変更で、
 * **同じ状態を読む残り4本（機能説明・開始ボタン・短文例外・保存プレビュー）を取り残した。**
 * 「直した場所の隣が残る」型（→ lessons L14）で、**数え直したのは実機レビューだった。**
 * 文言は型では守れないので、走査で数える。
 *
 * ## 見ているもの
 *
 * 蒸留UIのソースに、候補の単位を「文」と書いた表示文字列が無いこと。
 *
 * ## 見ていないもの
 *
 * - **文言の自然さ**。「箇所」が日本語として妥当かはレビューが見る
 * - 蒸留UIが他ファイルへ増えた場合。増やすときは [DISTILL_UI_SOURCES] へ足す
 * - `本文` `文字` のような、単位ではない「文」の用法（意図的に対象外）
 */
class DistillCandidateUnitCopyTest {

    @Test
    fun `蒸留の画面文言は候補の単位を文と決めつけない`() {
        val violations = DISTILL_UI_SOURCES.flatMap { relativePath ->
            val file = sourceRoot().resolve(relativePath)
            require(file.isFile) { "蒸留UIのソースが見つかりません: $relativePath" }
            file.readLines().withIndex().flatMap { (index, line) ->
                UNIT_AS_SENTENCE.mapNotNull { pattern ->
                    pattern.find(line)?.let { "${file.name}:${index + 1} ${it.value}（${line.trim()}）" }
                }
            }
        }

        assertTrue(
            "候補の単位を「文」と決めつけた表示文言が残っています。" +
                "候補には句・語句が混ざるので、単位に依存しない語を使ってください:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    private fun sourceRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        return listOf(workingDirectory.resolve("src"), workingDirectory.resolve("app/src"))
            .firstOrNull { it.isDirectory }
            ?: error("app/src が見つかりません（作業ディレクトリ: $workingDirectory）")
    }

    private companion object {
        val DISTILL_UI_SOURCES = listOf("main/java/com/example/newproject/ui/screen/AiTab.kt")

        /**
         * 候補の単位として「文」を使っている形。
         *
         * **`本文` `文字` を巻き込まないよう、数量・指示・修飾を伴う形だけを数える。**
         */
        val UNIT_AS_SENTENCE = listOf(
            Regex("""[0-9]文"""),
            Regex("""\}文"""),
            Regex("""重要文"""),
            Regex("""した文"""),
            Regex("""の1文""")
        )
    }
}
