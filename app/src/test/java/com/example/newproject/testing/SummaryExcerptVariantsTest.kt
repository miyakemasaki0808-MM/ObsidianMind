package com.example.newproject.testing

import com.example.newproject.ai.PromptBudget
import com.example.newproject.model.NoteExcerptLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **実機で回す前に、変種の形だけを机上で固定する。**
 *
 * 変種の中身が壊れていても端末では緑に見える — 生成は返るし採点も通る。
 * **気づくのは表を読んだときで、そのときには実機セッションが終わっている。**
 * 予算超過・プロンプトの切り詰め・「変種が実は同じ」は、どれもここで落とせる。
 *
 * **出力の良し悪しは見ない**（見られない）。それは実機で採る。
 */
class SummaryExcerptVariantsTest {

    @Test
    fun `変種は自分の予算を超えない`() {
        val violations = corpusNotes().flatMap { (name, content) ->
            SUMMARY_EXCERPT_VARIANTS.mapNotNull { variant ->
                val excerpt = variant.buildExcerpt(content)
                val occupied = excerpt.text.length +
                    if (excerpt.isAbridged) NoteExcerptLimits.ABRIDGED_NOTICE_PREFIX.length else 0
                if (occupied <= variant.budget) null
                else "$name / ${variant.name}: $occupied 文字（予算 ${variant.budget}）"
            }
        }

        assertTrue("抜粋が予算を超えています:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun `完成プロンプトが切り詰められない`() {
        val truncated = corpusNotes().flatMap { (name, content) ->
            SUMMARY_EXCERPT_VARIANTS.mapNotNull { variant ->
                val prompt = variant.buildPrompt(name, content)
                if (PromptBudget.TRUNCATION_MARKER !in prompt) null
                else "$name / ${variant.name}: ${prompt.length} 文字"
            }
        }

        assertTrue(
            "完成プロンプトが上限で切られています。**切られた入力どうしを比べても意味が無い**ので、" +
                "予算のほうを見直してください:\n${truncated.joinToString("\n")}",
            truncated.isEmpty()
        )
    }

    /**
     * **変種が実は同じ、を防ぐ。** 予算を超える長文で本番と旧方式が同じ文字列を作るなら、
     * 実機で何を比べても差は出ない。
     */
    @Test
    fun `予算を超える本文では変種どうしが異なる抜粋を作る`() {
        val (name, content) = corpusNotes().maxByOrNull { it.second.length }!!
        assertTrue(
            "$name が抜粋予算を超えていません。比較の材料になりません",
            content.length > NoteExcerptLimits.SUMMARY
        )

        val texts = SUMMARY_EXCERPT_VARIANTS.map { it.buildExcerpt(content).text }
        assertEquals("変種の数だけ抜粋が要ります", SUMMARY_EXCERPT_VARIANTS.size, texts.size)
        assertNotEquals("本番と旧方式が同じ抜粋を作っています", texts[0], texts[1])
        assertNotEquals("予算を倍にしても抜粋が変わっていません", texts[0], texts[2])
    }

    /**
     * **予算内の短いノートでは全変種が原文そのままになる。**
     * ここが崩れると、短いノートの差が「切り出し方の差」に見えてしまう。
     */
    @Test
    fun `予算内の本文はどの変種も原文をそのまま渡す`() {
        val (name, content) = corpusNotes().minByOrNull { it.second.length }!!
        assertTrue("$name が予算を超えています", content.length <= NoteExcerptLimits.SUMMARY)

        SUMMARY_EXCERPT_VARIANTS.forEach { variant ->
            val excerpt = variant.buildExcerpt(content)
            assertEquals("${variant.name} が短い本文を加工しています", content, excerpt.text)
            assertFalse("${variant.name} が短い本文に注意書きを付けています", excerpt.isAbridged)
        }
    }

    @Test
    fun `旧方式は代理対を割らない`() {
        val content = "あ".repeat(NoteExcerptLimits.SUMMARY) + "𠮷野家".repeat(40)
        val budget = NoteExcerptLimits.ABRIDGED_NOTICE_PREFIX.length + 3

        val text = headOnlyExcerpt(content, budget).text

        assertFalse(
            "末尾に壊れた文字が残っています",
            text.isNotEmpty() && text.last().isHighSurrogate()
        )
    }

    private fun corpusNotes(): List<Pair<String, String>> = FixedCorpus.notes().toList()
}
