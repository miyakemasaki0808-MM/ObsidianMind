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
    fun `極小予算ではタイトルを切ってでも予算内に収める`() {
        // ID・区切りまで含めてちょうど収まる予算なら、詳細を捨てて1件返す。
        val line = renderCandidatesWithinBudget(listOf(candidate()), charBudget = 7, maxSnippetLen = 50, minSnippetLen = 4).single()
        assertNull(line.detail)
        assertEquals("C01 | T", line.renderForPrompt())

        // タイトルぶんが無ければ切る。**予算を超えて返さない。**
        val cut = renderCandidatesWithinBudget(listOf(candidate()), charBudget = 6, maxSnippetLen = 50, minSnippetLen = 4).single()
        assertEquals("C01 | ", cut.renderForPrompt())

        // IDの体裁すら入らないなら何も返さない。
        assertTrue(
            renderCandidatesWithinBudget(listOf(candidate()), charBudget = 1, maxSnippetLen = 50, minSnippetLen = 4).isEmpty()
        )
    }

    /**
     * **タイトルだけでも予算を超える場合が最後まで塞がっていなかった。**
     * 旧実装は最終フォールバックで全件をそのまま返し、収まりを確かめ直していなかった。
     */
    @Test
    fun `長いタイトルが並んでも返す前に必ず予算内へ収める`() {
        val candidates = (1..8).map {
            CandidateContext(id = "C0$it", title = "とても長いタイトル".repeat(12) + it, snippet = "")
        }
        val budget = 300
        val lines = renderCandidatesWithinBudget(candidates, charBudget = budget, maxSnippetLen = 150, minSnippetLen = 10)

        val total = lines.sumOf { it.renderForPrompt().length } + (lines.size - 1).coerceAtLeast(0)
        assertTrue("total=$total budget=$budget", total <= budget)
        assertTrue("候補が1件も残っていない", lines.isNotEmpty())
        // 落とすのは末尾から。上位の候補（再ランク済みの良い順）は残す。
        assertEquals("C01", lines.first().id)
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
