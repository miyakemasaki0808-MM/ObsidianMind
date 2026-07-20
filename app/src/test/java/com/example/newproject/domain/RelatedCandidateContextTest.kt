package com.example.newproject.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 2a: 候補スニペット抽出と入力バジェット整形の回帰テスト（Uri非依存）。
// 実データでのスニペット長・バジェットの最適値はPixel実機計測で詰める（定数は可変）。
class RelatedCandidateContextTest {

    // --- extractRelatedSnippet / stripFrontmatter ---

    @Test
    fun `frontmatterと見出しを飛ばし最初の段落を1行へ畳む`() {
        val content = "---\ntags: [a]\n---\n\n# 見出し\n\n本文の一行目\n二行目\n\n次の段落"
        assertEquals("本文の一行目 二行目", extractRelatedSnippet(content, 100))
    }

    @Test
    fun `maxLenで切り詰める`() {
        assertEquals("abcd", extractRelatedSnippet("abcdefghij", 4))
    }

    @Test
    fun `本文が無ければ空文字`() {
        assertEquals("", extractRelatedSnippet("# 見出しだけ", 100))
    }

    @Test
    fun `閉じないfrontmatterは除去しない`() {
        val content = "---\ntags\n本文"
        assertEquals(content, stripFrontmatter(content))
    }

    // --- renderCandidatesWithinBudget（ID・タイトルは常に残す。削減順: タグ→aliases→本文） ---

    private fun candidate() = CandidateContext(
        id = "C01",
        title = "T",
        snippet = "ABCDEFGHIJ",
        tags = listOf("tg"),
        aliases = listOf("al")
    )

    @Test
    fun `予算に余裕があれば本文・aliases・タグを全て含む`() {
        val line = renderCandidatesWithinBudget(listOf(candidate()), charBudget = 100, maxSnippetLen = 50, minSnippetLen = 4).single()
        val detail = line.detail!!
        assertTrue(detail.contains("ABCDEFGHIJ"))
        assertTrue(detail.contains("aka al"))
        assertTrue(detail.contains("tags: tg"))
    }

    @Test
    fun `超過時はまずタグを落とす`() {
        val line = renderCandidatesWithinBudget(listOf(candidate()), charBudget = 35, maxSnippetLen = 50, minSnippetLen = 4).single()
        val detail = line.detail!!
        assertFalse(detail.contains("tags:"))
        assertTrue(detail.contains("aka al"))
        assertTrue(detail.contains("ABCDEFGHIJ"))
    }

    @Test
    fun `さらに超過すればaliasesも落とす（本文は残す）`() {
        val line = renderCandidatesWithinBudget(listOf(candidate()), charBudget = 25, maxSnippetLen = 50, minSnippetLen = 4).single()
        assertEquals("ABCDEFGHIJ", line.detail)
    }

    @Test
    fun `本文が最後に短縮される`() {
        val line = renderCandidatesWithinBudget(listOf(candidate()), charBudget = 17, maxSnippetLen = 50, minSnippetLen = 4).single()
        val detail = line.detail!!
        assertTrue(detail.startsWith("ABC"))
        assertFalse(detail.contains("ABCDEFGHIJ")) // 全文ではない
        assertTrue(line.renderForPrompt().length <= 17)
    }

    @Test
    fun `極小予算でもID・タイトルは残す（詳細はnull）`() {
        val line = renderCandidatesWithinBudget(listOf(candidate()), charBudget = 1, maxSnippetLen = 50, minSnippetLen = 4).single()
        assertNull(line.detail)
        assertEquals("C01 | T", line.renderForPrompt())
    }

    @Test
    fun `複数候補でも総量が予算内に収まる`() {
        val candidates = (1..5).map {
            CandidateContext(id = "C0$it", title = "T$it", snippet = "本文$it".repeat(30))
        }
        val budget = 120
        val lines = renderCandidatesWithinBudget(candidates, charBudget = budget, maxSnippetLen = 150, minSnippetLen = 10)
        val total = lines.sumOf { it.renderForPrompt().length } + (lines.size - 1)
        assertTrue("total=$total budget=$budget", total <= budget)
        assertEquals(5, lines.size) // 候補は落とさず、詳細を削って収める
    }
}
