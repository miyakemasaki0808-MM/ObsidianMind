package com.example.newproject.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **複数行の値を埋めても、テンプレート側の字下げがプロンプトへ漏れない**ことを固定する。
 *
 * ## なぜ要るか
 *
 * `trimIndent()` は**補間後の文字列**に効く。複数行の値を埋めるとその2行目以降が
 * インデント0の行として混ざり、共通インデントが0と判定されて**テンプレート側の
 * 12スペースが全行に残る**。本文抜粋・候補一覧・会話履歴はいずれも複数行になり得るので、
 * 実運用ではほぼ常に発生していた。
 *
 * **既存の回帰テストは値を単一行で比較していたので、緑のまま通り抜けた。**
 * ここでは全 builder を**複数行の値で**組み立てて判定する。
 *
 * ## 見ているもの
 *
 * 1. プロンプトが空白で始まらないこと。
 *    テンプレート先頭は必ず指示文なので、`trimIndent()` が効かなかった瞬間にここが崩れる。
 *    **共通インデントは全行から算出されるため、値がどこに埋まっていても先頭行が巻き込まれる** —
 *    つまりこの1点でこの不具合の全発生形を捕まえられる。
 * 2. 単一行の値で組んだときの**静的な行**が、複数行の値でもそのまま現れること。
 *    先頭行だけでは、テンプレート中ほどの字下げ崩れを見落とし得る。
 */
class PromptIndentationTest {

    @Test
    fun `全プロンプトが複数行の値でも字下げを持ち込まない`() {
        PromptSamples.all(MULTI_LINE_VALUE).forEach { (name, prompt) ->
            assertTrue(
                "$name: プロンプトが空白で始まっている（trimIndent が効いていない）。\n" +
                    prompt.lines().take(3).joinToString("\n") { "[$it]" },
                prompt.first() != ' '
            )
            prompt.lines().forEach { line ->
                assertTrue(
                    "$name: 余分な先頭空白を持つ行がある: [$line]",
                    !line.startsWith(TEMPLATE_INDENT)
                )
            }
        }
    }

    @Test
    fun `静的な行は値が複数行になっても変わらない`() {
        val single = PromptSamples.all(SINGLE_LINE_VALUE).toMap()
        val multi = PromptSamples.all(MULTI_LINE_VALUE).toMap()

        single.forEach { (name, expected) ->
            val actualLines = requireNotNull(multi[name]) { "$name の組み立てが片方にしかない" }.lines()
            expected.lines().filterNot { it.contains(PromptSamples.MARK) }.forEach { staticLine ->
                assertTrue(
                    "$name: 値を複数行にしたら静的な行が変わった: [$staticLine]",
                    actualLines.contains(staticLine)
                )
            }
        }
    }

    /** builder が増えたらここへ足すまで落ちる（`PromptGenerationCoverageTest` と同じ形）。 */
    @Test
    fun `本番の全 builder を組み立てている`() {
        assertEquals(
            "字下げ・上限の検査から漏れている builder があります。PromptSamples.all() へ足してください。",
            PromptSamples.declaredBuilders().toSortedSet(),
            PromptSamples.all(SINGLE_LINE_VALUE).map { it.first }.toSortedSet()
        )
    }

    private companion object {
        val SINGLE_LINE_VALUE = "${PromptSamples.MARK}1"
        val MULTI_LINE_VALUE = "${PromptSamples.MARK}1\n${PromptSamples.MARK}2"

        /** テンプレート側の字下げ（raw string のインデント）。これが漏れていたら不具合。 */
        const val TEMPLATE_INDENT = "    "
    }
}
