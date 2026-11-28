package com.example.newproject.domain

import com.example.newproject.domain.markdown.parseMarkdownBlocks
import com.example.newproject.model.NoteForm
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ノートの型の決め方（→ `docs/dev/features/note_typesetting.md` §5.9）。
 *
 * 辞書は完全一致で照らすので、**当たるべき見出しと、近いが当たってはいけない見出しを両方置く**（→ lessons L67）。
 */
class NoteFormJudgeTest {

    private fun formOf(body: String, cssClasses: List<String> = emptyList()): NoteForm =
        noteFormOf(cssClasses, parseMarkdownBlocks(body))

    private val techSkeleton = """
        # enumerate()
        ## 🎯 目的
        番号も一緒に受け取る。
        ## 🧪 構文
        本文
        ## ⚠️ 注意点
        本文
    """.trimIndent()

    private val readingSkeleton = """
        # 📘 コルーチンの本
        ## 🕓 概要
        - 開始日：2026-11-02
        ## 💡 要点メモ
        本文
        ## ✍️ コメント
        本文
    """.trimIndent()

    @Test
    fun `cssclasses の型の名前は骨格より先に効く`() {
        assertEquals(NoteForm.ReadingNote, formOf(techSkeleton, listOf("readingnote")))
        assertEquals(NoteForm.Standard, formOf(techSkeleton, listOf("standard")))
        assertEquals(NoteForm.TechBook, formOf(readingSkeleton, listOf("技術書")))
    }

    @Test
    fun `cssclasses は英字の大小と前後の空白を見ず、型の名前でないものは飛ばす`() {
        assertEquals(NoteForm.TechBook, formOf(readingSkeleton, listOf("wide-page", " TechBook ")))
        assertEquals(NoteForm.ReadingNote, formOf(readingSkeleton, listOf("wide-page", "cards")))
    }

    @Test
    fun `技術書の骨格は記号と番号を外した見出しの語で決まる`() {
        assertEquals(NoteForm.TechBook, formOf(techSkeleton))
        val numbered = """
            ## 1. 目的：
            ## 2. **構文**
            ## 📶レベル
        """.trimIndent()
        assertEquals(NoteForm.TechBook, formOf(numbered))
    }

    @Test
    fun `読書ノートの骨格はコードの枠があっても読書ノートに決まる`() {
        val withCode = readingSkeleton + "\n```kotlin\nlaunch { }\n```\n"
        assertEquals(NoteForm.ReadingNote, formOf(withCode))
    }

    @Test
    fun `骨格の語が1つだけなら骨格では決めず、コードの枠の有無で決まる`() {
        val oneWord = "## 概要\n本文\n## 次にやること\n本文"
        assertEquals(NoteForm.Standard, formOf(oneWord))
        assertEquals(NoteForm.TechBook, formOf(oneWord + "\n```python\nprint(1)\n```\n"))
    }

    @Test
    fun `両方の型の語が同じ数なら骨格では決めない`() {
        val tie = "## 目的\n## 構文\n## 概要\n## 要点メモ"
        assertEquals(NoteForm.Standard, formOf(tie))
        assertEquals(NoteForm.TechBook, formOf("$tie\n```sh\nls\n```\n"))
    }

    @Test
    fun `多いほうの型に決まる`() {
        assertEquals(NoteForm.TechBook, formOf("## 目的\n## 構文\n## 注意点\n## 概要\n## 要点メモ"))
        assertEquals(NoteForm.ReadingNote, formOf("## 概要\n## 要点メモ\n## コメント\n## 目的\n## 構文"))
    }

    @Test
    fun `辞書に近いが違う見出しは数えない`() {
        // 「関連」は両方の型の骨格にあるので辞書に無い。「構文の歴史」は「構文」と一致しない。
        val nearMiss = "## 関連\n## 構文の歴史\n## 目的と背景\n## まとめ"
        assertEquals(NoteForm.Standard, formOf(nearMiss))
    }

    @Test
    fun `見出しでない行の語とコードの枠の中の見出しは数えない`() {
        val notHeadings = """
            目的
            構文
            ```markdown
            ## 概要
            ## 要点メモ
            ```
        """.trimIndent()
        // 段落の語は数えない。コードの枠の中の見出しも数えず、枠があるので技術書になる。
        assertEquals(NoteForm.TechBook, formOf(notHeadings))
        assertEquals(NoteForm.Standard, formOf("目的\n\n構文\n\n注意点"))
    }

    @Test
    fun `見出しもコードの枠も無いノートは標準`() {
        assertEquals(NoteForm.Standard, formOf("朝の30分を組み替えた。\n\n- 服を前の晩に出す"))
        assertEquals(NoteForm.Standard, formOf(""))
    }

    @Test
    fun `見出しの語は頭と末尾の記号だけを外し、中の記号は残す`() {
        assertEquals("レベル", headingWord("📶レベル"))
        assertEquals("目的", headingWord("1. 目的："))
        assertEquals("構文", headingWord("**構文**"))
        assertEquals("解説・注意点", headingWord("⚠️ 解説・注意点"))
        assertEquals("ユースケースに沿った構文例", headingWord("🧪 ユースケースに沿った構文例"))
        assertEquals("", headingWord("🎯 ✨"))
    }
}
