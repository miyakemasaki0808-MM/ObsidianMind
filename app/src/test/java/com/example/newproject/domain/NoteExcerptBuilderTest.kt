package com.example.newproject.domain

import com.example.newproject.model.NoteExcerpt
import com.example.newproject.model.NoteExcerptLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteExcerptBuilderTest {

    private val budget = NoteExcerptLimits.RELATED

    @Test
    fun `予算内はMarkdownを解析せず無加工で返す`() {
        val content = "---\ntags: [保持]\n---\n# 見出し\n\n1. 番号付き"

        val excerpt = buildNoteExcerpt(content, content.length)

        assertEquals(content, excerpt.text)
        assertFalse(excerpt.isAbridged)
        assertWithinBudget(excerpt, content.length)
    }

    @Test
    fun `予算ちょうどは無加工で1文字超過から抜粋になる`() {
        val exact = "a".repeat(budget)
        val over = exact + "b"

        val exactExcerpt = buildNoteExcerpt(exact, budget)
        val overExcerpt = buildNoteExcerpt(over, budget)

        assertEquals(exact, exactExcerpt.text)
        assertFalse(exactExcerpt.isAbridged)
        assertTrue(overExcerpt.isAbridged)
        assertWithinBudget(exactExcerpt, budget)
        assertWithinBudget(overExcerpt, budget)
    }

    @Test
    fun `超過ノートは骨格と冒頭と末尾を含む`() {
        val content = """
            # 最初の見出し

            BEGIN_TOKEN ${"a".repeat(800)}

            ## 中間の見出し

            ${"m".repeat(800)}

            ### 最後の見出し

            ${"z".repeat(800)} END_TOKEN
        """.trimIndent()

        val excerpt = buildNoteExcerpt(content, budget)

        assertTrue(excerpt.text.contains("## Note outline"))
        assertTrue(excerpt.text.contains("# 最初の見出し"))
        assertTrue(excerpt.text.contains("### 最後の見出し"))
        assertTrue(excerpt.text.contains("## Beginning excerpt"))
        assertTrue(excerpt.text.contains("BEGIN_TOKEN"))
        assertTrue(excerpt.text.contains("(omitted)"))
        assertTrue(excerpt.text.contains("## Ending excerpt"))
        assertTrue(excerpt.text.contains("END_TOKEN"))
        assertWithinBudget(excerpt, budget)
    }

    @Test
    fun `見出しが多くても先頭と末尾を含む均等選抜になる`() {
        val headings = (0 until 40).joinToString("\n\n") { index ->
            "# HEADING-${index.toString().padStart(2, '0')}"
        }
        val content = "$headings\n\n${"body ".repeat(500)}"

        val excerpt = buildNoteExcerpt(content, budget)
        val outline = excerpt.text.substringAfter("## Note outline\n")
            .substringBefore("\n\n## Beginning excerpt")

        assertTrue(outline.contains("HEADING-00"))
        assertTrue(outline.contains("HEADING-39"))
        assertTrue(outline.lines().any { it == "…" })
        assertFalse(outline.contains("HEADING-01\n# HEADING-02\n# HEADING-03"))
        assertWithinBudget(excerpt, budget)
    }

    @Test
    fun `単一の超長文段落は冒頭と末尾の両方を残す`() {
        val content = "BEGIN_PARAGRAPH " + "x".repeat(2000) + " END_PARAGRAPH"

        val excerpt = buildNoteExcerpt(content, budget)

        assertTrue(excerpt.text.contains("BEGIN_PARAGRAPH"))
        assertTrue(excerpt.text.contains("END_PARAGRAPH"))
        assertTrue(excerpt.text.contains("## Ending excerpt"))
        assertWithinBudget(excerpt, budget)
    }

    @Test
    fun `巨大コードブロックは両端を残して閉じフェンスを復元する`() {
        val content = """
            ```kotlin
            BEGIN_CODE
            ${"x".repeat(2000)}
            END_CODE
            ```
        """.trimIndent()

        val excerpt = buildNoteExcerpt(content, budget)
        val beginning = excerpt.text.substringAfter("## Beginning excerpt\n")
            .substringBefore("\n\n(omitted)")
        val ending = excerpt.text.substringAfter("## Ending excerpt\n")

        assertTrue(beginning.startsWith("```\n"))
        assertTrue(beginning.endsWith("\n```"))
        assertTrue(beginning.contains("BEGIN_CODE"))
        assertTrue(ending.startsWith("```\n"))
        assertTrue(ending.endsWith("\n```"))
        assertTrue(ending.contains("END_CODE"))
        assertFalse(excerpt.text.contains("```kotlin"))
        assertWithinBudget(excerpt, budget)
    }

    @Test
    fun `巨大な表は捨てずに両端を残す`() {
        val rows = (0 until 80).joinToString("\n") { index -> "| row-$index | ${"x".repeat(20)} |" }
        val content = "| key | value |\n| --- | --- |\n$rows"

        val excerpt = buildNoteExcerpt(content, budget)

        assertTrue(excerpt.text.contains("| key | value |"))
        assertTrue(excerpt.text.contains("row-79"))
        assertTrue(excerpt.text.contains("## Ending excerpt"))
        assertWithinBudget(excerpt, budget)
    }

    @Test
    fun `巨大なリストは捨てずに両端を残す`() {
        val content = (0 until 100).joinToString("\n") { index ->
            "- item-${index.toString().padStart(3, '0')} ${"x".repeat(12)}"
        }

        val excerpt = buildNoteExcerpt(content, budget)

        assertTrue(excerpt.text.contains("- item-000"))
        assertTrue(excerpt.text.contains("item-099"))
        assertTrue(excerpt.text.contains("## Ending excerpt"))
        assertWithinBudget(excerpt, budget)
    }

    @Test
    fun `見出しなしノートも冒頭と末尾を残す`() {
        val content = "BEGIN_NO_HEADING " + "x".repeat(1600) + " END_NO_HEADING"

        val excerpt = buildNoteExcerpt(content, budget)

        assertFalse(excerpt.text.contains("## Note outline"))
        assertTrue(excerpt.text.contains("BEGIN_NO_HEADING"))
        assertTrue(excerpt.text.contains("END_NO_HEADING"))
        assertWithinBudget(excerpt, budget)
    }

    @Test
    fun `全文が見出しだけでも予算内へ収める`() {
        val content = (0 until 120).joinToString("\n") { "# 見出し-$it" }

        val excerpt = buildNoteExcerpt(content, budget)

        assertTrue(excerpt.text.contains("## Note outline"))
        assertTrue(excerpt.text.contains("見出し-0"))
        assertTrue(excerpt.text.contains("見出し-119"))
        assertWithinBudget(excerpt, budget)
    }

    @Test
    fun `超過ノートのfrontmatterは除去する`() {
        val content = """
            ---
            tags: [SECRET_FRONTMATTER_TAG]
            aliases: [別名]
            ---
            # 本文

            BEGIN_BODY ${"x".repeat(1600)} END_BODY
        """.trimIndent()

        val excerpt = buildNoteExcerpt(content, budget)

        assertFalse(excerpt.text.contains("SECRET_FRONTMATTER_TAG"))
        assertFalse(excerpt.text.contains("aliases:"))
        assertTrue(excerpt.text.contains("BEGIN_BODY"))
        assertTrue(excerpt.text.contains("END_BODY"))
        assertWithinBudget(excerpt, budget)
    }

    @Test
    fun `超過ノートでもordered listの番号はモデルへ届く`() {
        val content = (1..100).joinToString("\n") { "$it. ordered-item-$it ${"x".repeat(8)}" }

        val excerpt = buildNoteExcerpt(content, budget)

        assertTrue(excerpt.text.contains("1. ordered-item-1"))
        assertFalse(excerpt.text.contains("- ordered-item-1"))
        assertWithinBudget(excerpt, budget)
    }

    @Test
    fun `超過ノートでもリストの入れ子段数はモデルへ届く`() {
        val content = (1..60).joinToString("\n") { i ->
            "- parent-$i ${"x".repeat(8)}\n    - child-$i ${"x".repeat(8)}"
        }

        val excerpt = buildNoteExcerpt(content, budget)

        assertTrue(excerpt.text.contains("- parent-1"))
        assertTrue(excerpt.text.contains("  - child-1"))
        assertWithinBudget(excerpt, budget)
    }

    @Test
    fun `記法が増えても抜粋は予算を超えない`() {
        // 番号と字下げの復元でブロックの再構成長が伸びるため、境界の取り方が変わる。
        // 「増加文字数」を固定すると壊れやすいので、不変条件の側を契約にする。
        val content = (1..80).joinToString("\n") { i ->
            "$i. step-$i ${"x".repeat(12)}\n        - note-$i ${"x".repeat(12)}"
        }

        // 実際に使われている予算（NoteExcerptLimits）を全部通す
        listOf(600, 1200, 1500, 3500).forEach { b ->
            assertWithinBudget(buildNoteExcerpt(content, b), b)
        }
    }

    @Test
    fun `圧縮後に全ブロックが入るなら中略と末尾ラベルを出さない`() {
        val content = "# 見出し" + "\n".repeat(budget) + "短い本文"

        val excerpt = buildNoteExcerpt(content, budget)

        assertTrue(excerpt.isAbridged)
        assertTrue(excerpt.text.contains("## Beginning excerpt"))
        assertFalse(excerpt.text.contains("(omitted)"))
        assertFalse(excerpt.text.contains("## Ending excerpt"))
        assertWithinBudget(excerpt, budget)
    }

    @Test
    fun `骨格と本文で見出しが重複しても予算を超えない`() {
        val content = """
            # DUPLICATE_HEADING

            ${"begin ".repeat(200)}

            ## MIDDLE

            ${"middle ".repeat(200)}

            ### END

            ${"end ".repeat(200)}
        """.trimIndent()

        val excerpt = buildNoteExcerpt(content, budget)

        assertTrue(excerpt.text.split("DUPLICATE_HEADING").size - 1 >= 2)
        assertWithinBudget(excerpt, budget)
    }

    @Test
    fun `注意書きを収められない予算での切り出しは拒否する`() {
        val tooSmall = NoteExcerptLimits.ABRIDGED_NOTICE_PREFIX.length - 1
        val content = "x".repeat(tooSmall + 1)

        val error = runCatching { buildNoteExcerpt(content, tooSmall) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `負の予算は拒否する`() {
        buildNoteExcerpt("本文", -1)
    }

    private fun assertWithinBudget(excerpt: NoteExcerpt, expectedBudget: Int) {
        val noticeLength = if (excerpt.isAbridged) NoteExcerptLimits.ABRIDGED_NOTICE_PREFIX.length else 0
        assertTrue(
            "抜粋と注意書き・区切りが予算を超えています: " +
                "${excerpt.text.length} + $noticeLength > $expectedBudget",
            excerpt.text.length + noticeLength <= expectedBudget
        )
    }
}
