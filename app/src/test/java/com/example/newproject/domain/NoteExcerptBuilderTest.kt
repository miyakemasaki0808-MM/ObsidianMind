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

        // 予算は定数から引く。値を直書きすると、上限を動かしたときに
        // 「もう使われていない値だけを通すテスト」が残って気づけない。
        listOf(
            NoteExcerptLimits.SUMMARY,
            NoteExcerptLimits.RELATED,
            NoteExcerptLimits.SECTION
        ).forEach { b ->
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

    @Test
    fun `頭の情報行の塊は冒頭に数えない`() {
        val content = """
            # 設計書

            **状態:** 実装済み・稼働中
            **最終検証:** 2026-09-12 / `23dce6b`
            **関連コード:** `NoteViewModel.kt` / `controller/NoteSessionCoordinator.kt`

            BODY_START 本文の説明。${"a".repeat(800)}

            ## 末尾

            ${"z".repeat(600)} END_TOKEN
        """.trimIndent()

        val excerpt = buildNoteExcerpt(content, budget)

        // 見出しは冒頭に残り、その直後から本文が始まる。
        assertTrue(excerpt.text.beginningSection().startsWith("# 設計書\n\nBODY_START"))
        assertFalse(excerpt.text.contains("最終検証"))
        assertTrue(excerpt.text.contains("END_TOKEN"))
        assertWithinBudget(excerpt, budget)
    }

    @Test
    fun `箇条書きの頭の情報も冒頭に数えない`() {
        val content = """
            ## 概要
            - 開始日　　：2025/05/23
            - 現在位置　：第2章から
            - ステータス：読書中

            BODY_START ${"a".repeat(900)}
        """.trimIndent()

        val beginning = buildNoteExcerpt(content, budget).text.beginningSection()

        assertTrue(beginning.startsWith("## 概要\n\nBODY_START"))
    }

    @Test
    fun `2行の頭と、ラベル行でない行が混じる頭は冒頭に残る`() {
        val twoLines = "**日付:** 2026-04-29\n**プロジェクト:** 天気概況\n\n${"a".repeat(900)}"
        val mixed = "**状態:** 稼働中\n**最終検証:** 2026-09-12\n本文が続く行\n**関連:** なし\n\n${"a".repeat(900)}"

        assertTrue(buildNoteExcerpt(twoLines, budget).text.beginningSection().startsWith("**日付:**"))
        assertTrue(buildNoteExcerpt(mixed, budget).text.beginningSection().startsWith("**状態:**"))
    }

    @Test
    fun `頭の塊の後ろの用語と説明の並びは冒頭に残る`() {
        val content = """
            - 科目：科目A テクノロジ系
            - 出題数目安：約3〜5問
            - 難易度：★★★☆☆

            - **フォールトトレラント**：障害が起きても継続稼働
            - **フェールセーフ**：障害時に安全な状態へ移行
            - **フェールソフト**：一部機能を落としてでも継続

            ${"a".repeat(900)}
        """.trimIndent()

        val beginning = buildNoteExcerpt(content, budget).text.beginningSection()

        assertTrue(beginning.startsWith("- **フォールトトレラント**"))
    }

    @Test
    fun `頭の情報だけを省いて全部入るなら中略を出さない`() {
        val content = """
            **状態:** 実装済み
            **最終検証:** 2026-09-12
            **関連コード:** ${"`a.kt` / ".repeat(100)}

            BODY_ONLY 短い本文。
        """.trimIndent()

        val excerpt = buildNoteExcerpt(content, budget)

        assertTrue(content.length > budget)
        assertTrue(excerpt.isAbridged)
        assertTrue(excerpt.text.contains("BODY_ONLY"))
        assertFalse(excerpt.text.contains("関連コード"))
        assertFalse(excerpt.text.contains("(omitted)"))
        assertWithinBudget(excerpt, budget)
    }

    @Test
    fun `頭の情報しか無い本文は末尾として残り空にならない`() {
        // frontmatter で原文だけが予算を超え、解析後に残るのは頭の塊だけ。冒頭は塊を数えないので、末尾が採る。
        val content = "---\ndescription: " + "a".repeat(2000) + "\n---\n" +
            "開始日: 2026-10-01\n現在位置: 第2章\nステータス: 読書中"

        listOf(
            NoteExcerptLimits.FIELD,
            NoteExcerptLimits.RELATED,
            NoteExcerptLimits.SUMMARY,
            NoteExcerptLimits.SECTION
        ).forEach { budget ->
            val excerpt = buildNoteExcerpt(content, budget)

            assertTrue(excerpt.isAbridged)
            assertTrue(
                "予算 $budget で頭の塊が抜粋から消えています: ${excerpt.text}",
                listOf("開始日: 2026-10-01", "現在位置: 第2章", "ステータス: 読書中").all { excerpt.text.contains(it) }
            )
            assertWithinBudget(excerpt, budget)
        }
    }

    /** `## Beginning excerpt` の直後から、中略か末尾のラベルまで。 */
    private fun String.beginningSection(): String =
        substringAfter("## Beginning excerpt\n").substringBefore("\n\n(omitted)").substringBefore("\n\n## Ending excerpt")

    private fun assertWithinBudget(excerpt: NoteExcerpt, expectedBudget: Int) {
        val noticeLength = if (excerpt.isAbridged) NoteExcerptLimits.ABRIDGED_NOTICE_PREFIX.length else 0
        assertTrue(
            "抜粋と注意書き・区切りが予算を超えています: " +
                "${excerpt.text.length} + $noticeLength > $expectedBudget",
            excerpt.text.length + noticeLength <= expectedBudget
        )
    }
}
