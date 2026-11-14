package com.example.newproject.domain

import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 知識索引に入れるリンクの取り出しと、候補を絞る名前の鍵（→ docs/dev/system/knowledge_index.md §3・§6）。
 *
 * **押せるかは本文のリンクと同じ規則で決める。** 押せないのにバックリンクに出る、を作らない。
 * 鍵は**解決できるリンクを落とさない**ことを、解決そのもの（[resolveNoteLink]）と突き合わせて確かめる。
 */
class KnowledgeIndexLinksTest {

    /** `[x](パス)` を組む。`../` で始まるパスをソースに直接書くと、KDoc の相対リンクの検査が実在するファイルへのリンクとして数える。 */
    private fun md(path: String, label: String = "x") = "[$label]($path)"

    private fun note(path: String) = NoteFile(
        name = path.substringAfterLast('/'),
        ref = DocumentRef("ref:$path"),
        vaultRelativePath = path
    )

    @Test
    fun `押せるリンクだけを本文の順に取り出し、同じノートの見出し・画像・外部URL・コードは入れない`() {
        val content = listOf(
            "# 冒頭",
            "最初に [[習慣]] を読む。",
            "- 箇条の ${md("notes/睡眠.md")} も。",
            "> 引用の [[集中#深い集中|集中]] も。",
            "| 列 | 先 |",
            "|---|---|",
            "| 表 | [[表の先]] |",
            "同じノートの [[#冒頭]]、画像 [[図.png]]、外部 ${md("https://example.com")}、コード `[[偽物]]`。",
            "```",
            "[[柵の中]]",
            "```"
        ).joinToString("\n")

        val links = extractIndexedLinks(content)

        assertEquals(listOf("習慣", "notes/睡眠.md", "集中", "表の先"), links.map { it.target })
        assertEquals(listOf(0, 1, 2, 3), links.map { it.ordinal })
        assertEquals(
            listOf(IndexedLinkKind.NAME, IndexedLinkKind.PATH, IndexedLinkKind.NAME, IndexedLinkKind.NAME),
            links.map { it.kind }
        )
        assertEquals("深い集中", links[2].heading)
    }

    /** 描画は見出しの列数に合わせて余分なセルを捨てる。描かれないリンクを数えない（押せないのにバックリンクに出さない）。 */
    @Test
    fun `表は描かれるセルのリンクだけを取り、見出しの列より多いセルは数えない`() {
        val content = listOf(
            "| column | 二列目 |",
            "|---|---|",
            "| [[visible]] | [[二列目の先]] | [[hidden]] |",
            "| [[列が足りない行]] |",
            "| [[表示名つき\\|表示]] | 本文 |"
        ).joinToString("\n")

        assertEquals(listOf("visible", "二列目の先", "列が足りない行", "表示名つき"), extractIndexedLinks(content).map { it.target })
    }

    @Test
    fun `1文はリンクを含む文を見える文字で返し、表示名を出して記法の記号を出さない`() {
        val links = extractIndexedLinks("前置きの文です。今日は **[[習慣|よい習慣]]** を続けた。次の文。")

        assertEquals("今日は よい習慣 を続けた。", links.single().sentence)
    }

    @Test
    fun `リンクの中の句点では文を切らない`() {
        val links = extractIndexedLinks("前の文。[[なぜ？それでも。]] を読み返した。後ろの文。")

        assertEquals("なぜ？それでも。 を読み返した。", links.single().sentence)
    }

    /** 角括弧は文の分け方が元から守るが、コードの記法は守らない。記法の内側の句点で切ると、記号が割れて見える文字が変わる。 */
    @Test
    fun `コードの記法の中の句点では文を切らない`() {
        val links = extractIndexedLinks("前の文。`a。b` と [[習慣]] を書いた。後ろの文。")

        assertEquals("a。b と 習慣 を書いた。", links.single().sentence)
    }

    @Test
    fun `長い1文は末尾を…で切る`() {
        val long = "あ".repeat(300)
        val sentence = extractIndexedLinks("$long[[習慣]]$long。").single().sentence

        assertEquals(KnowledgeIndexLimits.MAX_SENTENCE_CHARACTERS, sentence.length)
        assertTrue(sentence.endsWith("…"))
    }

    @Test
    fun `節の見出しと、同じ見出しのうち何番目かを持つ`() {
        val content = listOf(
            "前置きの [[A]]。",
            "## まとめ",
            "一つ目の節。",
            "## 別の節",
            "[[B]] がある。",
            "## まとめ",
            "二つ目の節の [[C]]。"
        ).joinToString("\n")

        val links = extractIndexedLinks(content)

        assertEquals(listOf(null, "別の節", "まとめ"), links.map { it.sectionHeading })
        assertEquals("同じ見出しの2つ目", listOf(0, 0, 1), links.map { it.sectionHeadingOccurrence })
    }

    @Test
    fun `1つのノートから取るリンクの数に上限を置く`() {
        val content = (0 until KnowledgeIndexLimits.MAX_LINKS_PER_NOTE + 20).joinToString("\n\n") { "[[n$it]] を読む。" }

        assertEquals(KnowledgeIndexLimits.MAX_LINKS_PER_NOTE, extractIndexedLinks(content).size)
    }

    @Test
    fun `取り出したリンクは本文のリンクと同じ形へ戻せる`() {
        val link = extractIndexedLinks("[[フォルダ/名前#見出し]] を見る。").single()

        assertEquals(NoteLink.ByName("フォルダ/名前", "見出し"), link.toNoteLink())
    }

    /**
     * **解決できるリンクを、鍵による絞り込みが落とさない。** 相対・根から・フォルダつきの `[[…]]` で、
     * `%20`・符号化した日本語・文字としての `%` を含む入力を、解決そのものと突き合わせる。
     */
    @Test
    fun `解決が今のノートへ着くリンクは、どれも今のノートの鍵を持つ`() {
        val notes = listOf(
            note("notes/my note.md"),
            note("notes/日本語のノート.md"),
            note("notes/100% 達成.md"),
            note("notes/100%25 達成.md"),
            note("journal/2026/振り返り.md"),
            note("journal/today.md")
        )
        val index = NoteLinkIndex(notes)
        val source = notes.first { it.vaultRelativePath == "journal/today.md" }
        val inputs = listOf(
            md("my%20note.md"), md("my note.md"), md("/notes/my%20note.md"), md("../notes/my%20note.md"),
            md("../notes/%E6%97%A5%E6%9C%AC%E8%AA%9E%E3%81%AE%E3%83%8E%E3%83%BC%E3%83%88.md"),
            md("/notes/%E6%97%A5%E6%9C%AC%E8%AA%9E%E3%81%AE%E3%83%8E%E3%83%BC%E3%83%88.md"),
            md("../notes/100%25%20%E9%81%94%E6%88%90.md"), md("/notes/100% 達成.md"),
            "[[notes/my%20note]]", "[[notes/my note]]", "[[my note]]", "[[My Note]]",
            "[[notes/日本語のノート]]", "[[日本語のノート]]", "[[100% 達成]]", "[[100%25 達成]]",
            "[[2026/振り返り]]", "[[journal/2026/振り返り]]", md("2026/%E6%8C%AF%E3%82%8A%E8%BF%94%E3%82%8A.md")
        )

        var resolved = 0
        inputs.forEach { text ->
            val link = extractIndexedLinks(text).singleOrNull() ?: error("取り出せない: $text")
            val result = resolveNoteLink(link.toNoteLink(), source.ref, index)
            if (result is NoteLinkResolution.Open) {
                resolved++
                assertTrue(
                    "$text は ${result.note.title} へ解けるのに鍵 ${link.keys} が ${noteKeyOf(result.note.title)} を持たない",
                    noteKeyOf(result.note.title) in link.keys
                )
            }
        }
        assertEquals("入力の全部が解けることを前提にしている", inputs.size, resolved)
    }

    @Test
    fun `本文の指紋は中身が同じなら同じで、違えば違う`() {
        assertEquals(contentFingerprint("同じ本文"), contentFingerprint("同じ本文"))
        assertTrue(contentFingerprint("本文A") != contentFingerprint("本文B"))
    }
}
