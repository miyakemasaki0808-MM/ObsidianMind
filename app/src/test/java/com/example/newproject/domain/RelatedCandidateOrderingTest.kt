package com.example.newproject.domain

import com.example.newproject.ai.PromptBuilder
import com.example.newproject.ai.RelatedCandidateLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 1a: 関連ノートAI候補の純ロジックとプロンプト整形の回帰テスト。
// android.net.Uri を伴うAI応答→NoteFile解決・可用性分岐は、素のJVMでは
// Uriが構築できないため実機検証に委ねる（プロジェクト既存の方針に準拠）。
class RelatedCandidateOrderingTest {

    // --- extractHexPrefix ---

    @Test
    fun `先頭4桁の16進のみを大文字で抽出する`() {
        assertEquals("0F01", extractHexPrefix("0f01 ノート"))
        assertEquals("3ABC", extractHexPrefix("3abcdef 続き")) // 先頭4桁のみ
    }

    @Test
    fun `採番が無い・4桁未満・非16進は null`() {
        assertNull(extractHexPrefix("採番なしノート"))
        assertNull(extractHexPrefix("0F1 三桁で空白"))
        assertNull(extractHexPrefix("GHIJ 非16進"))
    }

    // --- orderRelatedCandidateTitles ---

    @Test
    fun `採番ありは 兄弟→カテゴリ→採番なし の順に並び 上位1桁が異なる候補は除外`() {
        val ordered = orderRelatedCandidateTitles(
            currentTitle = "0F01 現在ノート",
            candidateTitles = listOf(
                "3A10 別カテゴリ", // 上位1桁が異なる → 採番近接ゲートで除外
                "0A20 同カテゴリ", // 上位1桁一致・上位2桁不一致 → sameCategory
                "0F30 兄弟",       // 上位2桁一致 → sameGroup
                "採番なしメモ"      // noPrefix
            ),
            excludedTitles = emptySet(),
            limit = 40
        )
        assertEquals(listOf("0F30 兄弟", "0A20 同カテゴリ", "採番なしメモ"), ordered)
    }

    @Test
    fun `現ノートに採番が無いと入力順のまま上限だけ適用する`() {
        val titles = (1..50).map { "note$it" }
        val ordered = orderRelatedCandidateTitles(
            currentTitle = "採番なし現ノート",
            candidateTitles = titles,
            excludedTitles = emptySet(),
            limit = 40
        )
        assertEquals(40, ordered.size)
        assertEquals(titles.take(40), ordered)
    }

    @Test
    fun `除外は上限適用の前に行われ 空いた枠へ他カテゴリが繰り上がる`() {
        val siblings = (1..40).map { "0F%02d 兄弟".format(it) } // 上位2桁=0F の40件
        val excluded = "0F01 兄弟"                              // 決定的枠として1件除外
        val category = "0A99 カテゴリ"                          // 上位1桁一致の別カテゴリ

        val ordered = orderRelatedCandidateTitles(
            currentTitle = "0F00 現在",
            candidateTitles = siblings + category,
            excludedTitles = setOf(excluded),
            limit = 40
        )

        assertEquals(40, ordered.size)
        assertFalse("除外タイトルは含まれない", ordered.contains(excluded))
        // 上限で切る前に除外したため、空いた1枠へカテゴリ候補が繰り上がる。
        // （上限で切ってから除外していたら category は現れない）
        assertTrue("除外で空いた枠に別カテゴリが入る", ordered.contains(category))
    }

    @Test
    fun `除外は正規化タイトルで判定する（大小・拡張子・パスを無視）`() {
        val ordered = orderRelatedCandidateTitles(
            currentTitle = "採番なし",
            candidateTitles = listOf("Foo.md", "Bar"),
            excludedTitles = setOf("path/to/foo"), // 小文字・パス付き・拡張子なし
            limit = 40
        )
        assertEquals(listOf("Bar"), ordered)
    }

    @Test
    fun `同名でも別要素として並び順に保持される（ID採番の前提）`() {
        data class Cand(val id: Int, val title: String)
        val ordered = orderRelatedCandidates(
            currentTitle = "採番なし",
            candidates = listOf(Cand(1, "重複名"), Cand(2, "重複名"), Cand(3, "別名")),
            titleOf = { it.title },
            excludedTitles = emptySet(),
            limit = 40
        )
        assertEquals(listOf(1, 2, 3), ordered.map { it.id })
    }

    // --- buildRelatedNotesPrompt（ID提示・ID応答／[linked]撤去） ---

    @Test
    fun `プロンプトはID付き候補を提示しIDだけ返させる（linked撤去）`() {
        val prompt = PromptBuilder.buildRelatedNotesPrompt(
            currentTitle = "現在ノート",
            currentContent = "本文スニペット",
            candidates = listOf(
                RelatedCandidateLine("C01", "候補A"),
                RelatedCandidateLine("C02", "候補B")
            )
        )
        assertFalse(prompt.contains("[linked]"))
        assertFalse(prompt.contains("Prefer"))
        assertTrue(prompt.contains("C01 | 候補A"))
        assertTrue(prompt.contains("C02 | 候補B"))
        assertTrue(prompt.contains("Return only the IDs"))
    }
}
