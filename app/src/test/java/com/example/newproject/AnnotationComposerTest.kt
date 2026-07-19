package com.example.newproject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationComposerTest {

    // ── hasAnnotationBody ────────────────────────────────────────────────

    @Test
    fun `必須セクションに中身があれば本文ありと判定する`() {
        val generated = """
            ## 粒度評価
            このノートは概念の説明が簡潔にまとまっている。
            ## 補記すべき内容
            具体例が不足している。
        """.trimIndent()
        assertTrue(AnnotationComposer.hasAnnotationBody(generated))
    }

    @Test
    fun `片方のセクションにだけ中身があっても本文ありと判定する`() {
        val generated = """
            ## 粒度評価
            ## 補記すべき内容
            具体例が不足している。
        """.trimIndent()
        assertTrue(AnnotationComposer.hasAnnotationBody(generated))
    }

    @Test
    fun `見出しだけで中身が無ければ本文なしと判定する`() {
        val generated = """
            ## 粒度評価
            ## 補記すべき内容
        """.trimIndent()
        assertFalse(AnnotationComposer.hasAnnotationBody(generated))
    }

    @Test
    fun `必須セクション見出しが無ければ本文なしと判定する`() {
        assertFalse(AnnotationComposer.hasAnnotationBody("自由形式の回答テキストです。"))
        assertFalse(AnnotationComposer.hasAnnotationBody(""))
    }

    // ── buildAnnotationMarkdown ──────────────────────────────────────────

    @Test
    fun `ヘッダとメタ情報と本文を含むMarkdownを組み立てる`() {
        val body = """
            ## 粒度評価
            評価内容
            ## 補記すべき内容
            補記内容
        """.trimIndent()
        val markdown = AnnotationComposer.buildAnnotationMarkdown(
            title = "テストノート",
            createdAt = "2026-07-19 12:00",
            generatedBody = body
        )
        val lines = markdown.lines()
        assertEquals("# テストノート AI補記メモ", lines[0])
        assertTrue(markdown.contains("> Source: [[テストノート]]"))
        assertTrue(markdown.contains("> Created: 2026-07-19 12:00"))
        assertTrue(markdown.contains("## 粒度評価\n評価内容"))
    }

    @Test
    fun `どの行にも先頭インデントが混入しない`() {
        // trimIndent 時代の回帰テスト: 本文がインデント0の複数行でも
        // ヘッダ・メタ行に字下げが残らないこと（4スペース以上は
        // Obsidian でコードブロック扱いになるため）
        val body = "## 粒度評価\n評価内容\n## 補記すべき内容\n補記内容"
        val markdown = AnnotationComposer.buildAnnotationMarkdown("T", "2026-07-19 12:00", body)
        markdown.lines().forEach { line ->
            assertFalse("字下げ混入: '$line'", line.startsWith(" "))
        }
    }

    @Test
    fun `欠落した必須セクションは空見出しとして補完される`() {
        val markdown = AnnotationComposer.buildAnnotationMarkdown(
            title = "T",
            createdAt = "2026-07-19 12:00",
            generatedBody = "## 粒度評価\n評価のみ"
        )
        assertTrue(markdown.contains("## 補記すべき内容"))
    }

    @Test
    fun `両セクションが揃っていれば本文はそのまま維持される`() {
        val body = "## 粒度評価\nA\n\n## 補記すべき内容\nB"
        val markdown = AnnotationComposer.buildAnnotationMarkdown("T", "2026-07-19 12:00", body)
        assertTrue(markdown.endsWith(body))
    }
}
