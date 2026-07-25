package com.example.newproject.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// さがすタブのキーワード一致。フォールバックが画面文言「キーワード一致で表示しています」と
// 一致していることを担保する（以前は SAF の列挙順の先頭3件を返していた）。
class SearchKeywordMatchingTest {

    @Test
    fun `スコアはbigramの重なり数`() {
        assertEquals(4, keywordMatchScore("習慣づくり", "習慣づくりの記録"))
        assertEquals(1, keywordMatchScore("習慣づくり", "習慣について"))
        assertEquals(0, keywordMatchScore("習慣づくり", "料理のコツ"))
    }

    @Test
    fun `大文字小文字と空白は無視する`() {
        assertEquals(
            keywordMatchScore("ai設計", "AI設計メモ"),
            keywordMatchScore("AI 設計", "ai 設計 メモ")
        )
    }

    // 1文字クエリは bigram が作れない。部分一致で見ないと全件0点になり、
    // 「見つかりませんでした」しか出なくなる。
    @Test
    fun `1文字クエリは部分一致で拾う`() {
        assertEquals(1, keywordMatchScore("習", "習慣について"))
        assertEquals(0, keywordMatchScore("習", "料理のコツ"))
    }

    @Test
    fun `空クエリはどのタイトルにも一致しない`() {
        assertEquals(0, keywordMatchScore("", "習慣について"))
        assertEquals(0, keywordMatchScore("   ", "習慣について"))
    }

    // ── フォールバック（画面へそのまま出る検索結果）────────────────────────────

    @Test
    fun `一致度の高い順に返す`() {
        val notes = listOf("料理のコツ", "習慣について", "習慣づくりの記録")

        assertEquals(
            listOf("習慣づくりの記録", "習慣について"),
            pickByKeyword("習慣づくり", notes, limit = 3) { it }
        )
    }

    // 関係の無いノートを「キーワード一致」として見せない。0件なら画面は
    // 「見つかりませんでした。」になる。
    @Test
    fun `一致0件のノートは返さない`() {
        val notes = listOf("料理のコツ", "掃除の手順", "散歩の記録")

        assertTrue(pickByKeyword("量子力学", notes, limit = 3) { it }.isEmpty())
    }

    @Test
    fun `上限を超えては返さない`() {
        val notes = listOf("習慣づくり1", "習慣づくり2", "習慣づくり3", "習慣づくり4")

        assertEquals(3, pickByKeyword("習慣づくり", notes, limit = 3) { it }.size)
    }

    // 同点は元の並び順を保つ（安定ソート）。並びが実行のたびに変わらないこと。
    @Test
    fun `同点は元の並び順を保つ`() {
        val notes = listOf("習慣について", "習慣の記録", "習慣メモ")

        assertEquals(notes, pickByKeyword("習慣", notes, limit = 3) { it })
    }

    // ── 再現率カット（Nano へ渡す前の粗い絞り込み）────────────────────────────

    // こちらは精度を Nano が担保するので、一致0件も落とさず取りこぼしを避ける。
    @Test
    fun `再現率カットは一致0件も残す`() {
        val notes = listOf("料理のコツ", "掃除の手順", "習慣について", "散歩の記録", "洗濯のコツ")

        val cut = recallCutByKeyword("習慣", notes, limit = 3) { it }

        assertEquals(3, cut.size)
        assertEquals("習慣について", cut.first())
    }

    @Test
    fun `再現率カットは空クエリでも元の並びで上限まで返す`() {
        val notes = listOf("料理のコツ", "掃除の手順", "習慣について")

        assertEquals(listOf("料理のコツ", "掃除の手順"), recallCutByKeyword("", notes, limit = 2) { it })
    }
}
