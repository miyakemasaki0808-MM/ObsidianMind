package com.example.newproject.domain

import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.parseMarkdownBlocks
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 今のノートへのリンクを解いて1ノート1行にまとめる（→ docs/dev/features/backlinks.md §5・§8）。
 *
 * **出どころから本文のリンクと同じ規則で解き、今のノートへ着くものだけを残す。** バックリンクに出たノートのリンクを押すと、必ず今のノートへ戻る。
 */
class BacklinksTest {

    private fun note(path: String, lastModified: Long? = null) =
        NoteFile(name = path.substringAfterLast('/'), ref = DocumentRef("ref:$path"), lastModified = lastModified, vaultRelativePath = path)

    private fun candidate(
        source: String,
        target: String,
        ordinal: Int = 0,
        kind: IndexedLinkKind = IndexedLinkKind.NAME,
        sentence: String = "$target を読む。",
        section: String? = null,
        occurrence: Int = 0
    ) = BacklinkCandidate(source, ordinal, kind, target, heading = null, sectionHeading = section, sectionHeadingOccurrence = occurrence, sentence = sentence)

    private val current = note("notes/習慣.md")

    @Test
    fun `今のノートへ着くリンクだけを残し、同じ名前の別のノートへ着くリンクは除く`() {
        val sameName = note("other/習慣.md")
        val inOther = note("other/日記.md", lastModified = 2L)
        val inNotes = note("notes/振り返り.md", lastModified = 1L)
        val index = NoteLinkIndex(listOf(current, sameName, inOther, inNotes))

        val rows = backlinkRows(
            listOf(candidate("other/日記.md", "習慣"), candidate("notes/振り返り.md", "習慣")),
            current.ref,
            index
        )

        assertEquals("同じフォルダが先に選ばれるので、other の日記の [[習慣]] は other/習慣 へ着く", listOf("振り返り.md"), rows.map { it.note.title })
    }

    @Test
    fun `1ノート1行にまとめ、最初の1文とほかの箇所の数を出し、節の見出しと番目を持つ`() {
        val source = note("notes/日記.md")
        val index = NoteLinkIndex(listOf(current, source))

        val rows = backlinkRows(
            listOf(
                candidate("notes/日記.md", "習慣", ordinal = 2, sentence = "三つ目。"),
                candidate("notes/日記.md", "習慣", ordinal = 0, sentence = "最初に [[習慣]] を書いた。", section = "まとめ", occurrence = 1),
                candidate("notes/日記.md", "習慣", ordinal = 1, sentence = "二つ目。")
            ),
            current.ref,
            index
        )

        val row = rows.single()
        assertEquals("最初に [[習慣]] を書いた。", row.sentence)
        assertEquals(2, row.otherCount)
        assertEquals("まとめ", row.sectionHeading)
        assertEquals(1, row.sectionHeadingOccurrence)
    }

    @Test
    fun `出どころの更新日時が新しい順、同じなら題名の順に並べる`() {
        val old = note("notes/古い.md", lastModified = 1L)
        val newA = note("notes/新しいA.md", lastModified = 5L)
        val newB = note("notes/新しいB.md", lastModified = 5L)
        val undated = note("notes/日時なし.md")
        val index = NoteLinkIndex(listOf(current, old, newA, newB, undated))

        val rows = backlinkRows(
            listOf("notes/古い.md", "notes/新しいB.md", "notes/日時なし.md", "notes/新しいA.md").map { candidate(it, "習慣") },
            current.ref,
            index
        )

        assertEquals(listOf("新しいA.md", "新しいB.md", "古い.md", "日時なし.md"), rows.map { it.note.title })
    }

    @Test
    fun `出どころが走査の索引に無ければ解かずに除き、今のノート自身のリンクも除く`() {
        val index = NoteLinkIndex(listOf(current))

        val rows = backlinkRows(
            listOf(candidate("locked/見えない.md", "習慣"), candidate("notes/習慣.md", "習慣")),
            current.ref,
            index
        )

        assertEquals(emptyList<String>(), rows.map { it.note.title })
    }

    /**
     * 索引が数えた「同じ見出しのうち何番目か」で開くと、リンクのある節の見出しへ着く。
     * 数え方（取り出し側）と探し方（開く側）が別の規則になると、開く位置がずれる。
     */
    @Test
    fun `同じ見出しの2つ目にだけあるリンクは、索引の番目で開くと2つ目の見出しへ着く`() {
        val body = "## まとめ\n\n一つ目。\n\n## まとめ\n\n二つ目で [[習慣]] を書いた。\n"
        val link = extractIndexedLinks(body).single()
        val blocks = parseMarkdownBlocks(body)

        val opened = headingBlockIndex(blocks, checkNotNull(link.sectionHeading), link.sectionHeadingOccurrence)

        val headings = blocks.indices.filter { blocks[it] is MarkdownBlock.Heading }
        assertEquals(headings[1], opened)
    }

    @Test
    fun `パスで書いたリンクも出どころのフォルダから解く`() {
        val source = note("notes/sub/日記.md")
        val index = NoteLinkIndex(listOf(current, source))

        val rows = backlinkRows(
            listOf(candidate("notes/sub/日記.md", "../習慣.md", kind = IndexedLinkKind.PATH)),
            current.ref,
            index
        )

        assertEquals(listOf("日記.md"), rows.map { it.note.title })
    }
}
