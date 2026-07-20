package com.example.newproject

import com.example.newproject.ui.markdown.NoteSection
import com.example.newproject.ui.markdown.buildNoteSectionModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// NoteSectionModel.surroundingContext（クイズ等のAI入力用の周辺テキスト構築）のテスト。
class SurroundingContextTest {

    private val content = """
        # はじめに
        序文テキスト

        ## 概要
        概要本文

        ## 詳細
        詳細本文

        # まとめ
        まとめ本文
    """.trimIndent()

    private val model = buildNoteSectionModel(content)

    private fun section(title: String): NoteSection =
        model.sections.first { it.title == title }

    @Test
    fun `目標長に足りていればフォーカスセクションのみを返す`() {
        val result = model.surroundingContext(section("概要"), targetLength = 1)

        assertTrue(result.contains("概要本文"))
        assertFalse(result.contains("序文テキスト"))
        assertFalse(result.contains("詳細本文"))
    }

    @Test
    fun `目標長が大きければノート全体まで広がる`() {
        val result = model.surroundingContext(section("概要"), targetLength = 10_000)

        assertTrue(result.contains("序文テキスト"))
        assertTrue(result.contains("概要本文"))
        assertTrue(result.contains("詳細本文"))
        assertTrue(result.contains("まとめ本文"))
    }

    @Test
    fun `拡張は直前のブロックから始まりブロック単位で広がる`() {
        // 「## 詳細」セクション（約13文字）に対し target=14 で1ブロックだけ拡張させる。
        // 直前の「概要本文」ブロックが入り、見出し「## 概要」や後続の「まとめ」は入らない。
        val result = model.surroundingContext(section("詳細"), targetLength = 14)

        assertTrue(result.contains("概要本文"))
        assertTrue(result.contains("詳細本文"))
        assertFalse(result.contains("## 概要"))
        assertFalse(result.contains("まとめ本文"))
    }

    @Test
    fun `セクションがnullならノート先頭から切り出す`() {
        val result = model.surroundingContext(null, targetLength = 10)

        assertEquals(10, result.length)
        assertTrue(result.startsWith("# はじめに"))
    }

    @Test
    fun `sectionsに無い擬似セクションはノート先頭フォールバック`() {
        val pseudo = NoteSection(title = "ノート全体", level = 0, text = content)

        val result = model.surroundingContext(pseudo, targetLength = 10)

        assertTrue(result.startsWith("# はじめに"))
    }

    @Test
    fun `空コンテンツでは空文字を返す`() {
        val empty = buildNoteSectionModel("")

        assertEquals("", empty.surroundingContext(null))
    }
}
