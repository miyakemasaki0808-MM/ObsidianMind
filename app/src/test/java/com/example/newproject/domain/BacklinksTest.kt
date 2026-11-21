package com.example.newproject.domain

import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.parseMarkdownBlocks
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.NoteFile
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingVisit
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

    private fun read(at: Long) = SourceReading(lastReadAt = at, memoSectionKeys = emptySet())

    private fun candidate(
        source: String,
        target: String,
        ordinal: Int = 0,
        kind: IndexedLinkKind = IndexedLinkKind.NAME,
        sentence: String = "$target を読む。",
        section: String? = null,
        occurrence: Int = 0,
        sentenceLink: IntRange? = null
    ) = BacklinkCandidate(
        source, ordinal, kind, target,
        heading = null,
        sectionHeading = section,
        sectionHeadingOccurrence = occurrence,
        sentence = sentence,
        sentenceLink = sentenceLink
    )

    private val current = note("notes/習慣.md")

    /** 痕跡の無い（どれも読んだ記録が無い）ときの行。 */
    private fun rowsOf(candidates: List<BacklinkCandidate>, outgoing: List<NoteLink>, current: DocumentRef, index: NoteLinkIndex) =
        backlinkRows(backlinkSources(candidates, outgoing, current, index), emptyMap(), "習慣.md")

    @Test
    fun `今のノートへ着くリンクだけを残し、同じ名前の別のノートへ着くリンクは除く`() {
        val sameName = note("other/習慣.md")
        val inOther = note("other/日記.md", lastModified = 2L)
        val inNotes = note("notes/振り返り.md", lastModified = 1L)
        val index = NoteLinkIndex(listOf(current, sameName, inOther, inNotes))

        val rows = rowsOf(
            listOf(candidate("other/日記.md", "習慣"), candidate("notes/振り返り.md", "習慣")),
            emptyList(),
            current.ref,
            index
        )

        assertEquals("同じフォルダが先に選ばれるので、other の日記の [[習慣]] は other/習慣 へ着く", listOf("振り返り.md"), rows.map { it.note.title })
    }

    /**
     * 互いにリンクしているノートは、本文を読めば行ける（→ features/backlinks.md 判断5）。
     * 今のノートのリンクも同じ規則で今のノートから解き、**着いた先だけ**を除く。同じ名前の別のノートへ着く本文のリンクでは除かない。
     */
    @Test
    fun `今のノートの本文がリンクしている相手を除き、同じ名前の別のノートへ着く本文のリンクでは除かない`() {
        val mutual = note("notes/日記.md", lastModified = 3L)
        val sameNameSource = note("other/振り返り.md", lastModified = 2L)
        val sameNameTarget = note("notes/振り返り.md")
        val index = NoteLinkIndex(listOf(current, mutual, sameNameSource, sameNameTarget))

        val rows = rowsOf(
            listOf(candidate("notes/日記.md", "習慣"), candidate("other/振り返り.md", "習慣")),
            listOf(NoteLink.ByPath("日記.md", null), NoteLink.ByName("振り返り", null)),
            current.ref,
            index
        )

        assertEquals("本文の [[振り返り]] は同じフォルダの notes/振り返り へ着く", listOf(sameNameSource.ref), rows.map { it.note.ref })
    }

    @Test
    fun `1ノート1行にまとめ、最初の1文とほかの箇所の数を出し、節の見出しと番目を持つ`() {
        val source = note("notes/日記.md")
        val index = NoteLinkIndex(listOf(current, source))

        val rows = rowsOf(
            listOf(
                candidate("notes/日記.md", "習慣", ordinal = 2, sentence = "三つ目。"),
                candidate("notes/日記.md", "習慣", ordinal = 0, sentence = "最初に [[習慣]] を書いた。", section = "まとめ", occurrence = 1),
                candidate("notes/日記.md", "習慣", ordinal = 1, sentence = "二つ目。")
            ),
            emptyList(),
            current.ref,
            index
        )

        val row = rows.single()
        assertEquals("最初に [[習慣]] を書いた。", row.sentence)
        assertEquals(2, row.otherCount)
        assertEquals("まとめ", row.sectionHeading)
        assertEquals(1, row.sectionHeadingOccurrence)
    }

    /** 再会の入口なので、忘れかけている相手を上に出す（→ features/backlinks.md 判断3）。 */
    @Test
    fun `読んだ記録が無い出どころを先に、最後に読んだ日が古い順に並べ、同じなら更新の古い順、それも同じなら題名の順`() {
        val notes = listOf(
            note("notes/最近読んだ.md", lastModified = 1L),
            note("notes/同じ日B.md", lastModified = 3L),
            note("notes/未読で新しい.md", lastModified = 5L),
            note("notes/昔読んだ.md", lastModified = 9L),
            note("notes/同じ日A.md", lastModified = 3L),
            note("notes/未読で日時なし.md"),
            note("notes/未読で古い.md", lastModified = 1L),
            note("notes/同じ日で古い.md", lastModified = 2L)
        )
        val index = NoteLinkIndex(listOf(current) + notes)
        val sources = backlinkSources(notes.map { candidate(it.vaultRelativePath, "習慣") }, emptyList(), current.ref, index)
        val readings = mapOf(
            "notes/最近読んだ.md" to read(900L),
            "notes/昔読んだ.md" to read(100L),
            "notes/同じ日A.md" to read(500L),
            "notes/同じ日B.md" to read(500L),
            "notes/同じ日で古い.md" to read(500L)
        )

        val rows = backlinkRows(sources, readings, "習慣.md")

        assertEquals(
            listOf("未読で日時なし.md", "未読で古い.md", "未読で新しい.md", "昔読んだ.md", "同じ日で古い.md", "同じ日A.md", "同じ日B.md", "最近読んだ.md"),
            rows.map { it.note.title }
        )
    }

    /** ✎ は最初のリンクのある節に書いたメモだけ。見出しより前のリンクには付けない（→ features/backlinks.md 判断7）。 */
    @Test
    fun `最初のリンクのある節と同じ見出しにメモがあれば印を付け、別の節と見出しより前のリンクには付けない`() {
        val same = note("notes/同じ節.md")
        val other = note("notes/別の節.md")
        val before = note("notes/見出しの前.md")
        val index = NoteLinkIndex(listOf(current, same, other, before))
        val sources = backlinkSources(
            listOf(
                candidate("notes/同じ節.md", "習慣", section = "まとめ"),
                candidate("notes/別の節.md", "習慣", section = "まとめ"),
                candidate("notes/見出しの前.md", "習慣", section = null)
            ),
            emptyList(),
            current.ref,
            index
        )
        val memoIn = { section: String -> SourceReading(lastReadAt = 1L, memoSectionKeys = setOf(headingMatchKey(section))) }

        val rows = backlinkRows(
            sources,
            mapOf("notes/同じ節.md" to memoIn("まとめ"), "notes/別の節.md" to memoIn("導入"), "notes/見出しの前.md" to memoIn("まとめ")),
            "習慣.md"
        )

        assertEquals(
            mapOf("同じ節.md" to true, "別の節.md" to false, "見出しの前.md" to false),
            rows.associate { it.note.title to it.hasMemo }
        )
    }

    /** 題名が長いと1行を食うので「このノート」と出す。別名は自分が選んだ言葉なので替えない（→ features/backlinks.md 判断6）。 */
    @Test
    fun `1文の中の今のノートの題名をこのノートと出し、別名は替えない`() {
        fun replaced(sentence: String, shown: String, heading: String? = null): Pair<String, String?> {
            val start = sentence.indexOf(shown)
            val (text, range) = sentenceForCurrent(sentence, start until start + shown.length, heading, "習慣.md")
            return text to range?.let { text.substring(it) }
        }

        assertEquals("ここで このノート を使う。" to "このノート", replaced("ここで 習慣 を使う。", "習慣"))
        assertEquals("このノート を読む。" to "このノート", replaced("notes/習慣 を読む。", "notes/習慣"))
        assertEquals("このノート#まとめ を読む。" to "このノート#まとめ", replaced("習慣#まとめ を読む。", "習慣#まとめ", heading = "まとめ"))
        assertEquals("このノート を読む。" to "このノート", replaced("習慣.md を読む。", "習慣.md"))
        assertEquals("よい習慣 を続けた。" to "よい習慣", replaced("よい習慣 を続けた。", "よい習慣"))
        assertEquals("範囲が分からなければ替えない", "習慣 を読む。" to null, sentenceForCurrent("習慣 を読む。", null, null, "習慣.md"))
    }

    @Test
    fun `行は言い換えた1文と、言い換えた後のリンクの範囲を持つ`() {
        val source = note("notes/日記.md")
        val index = NoteLinkIndex(listOf(current, source))
        val sources = backlinkSources(
            listOf(candidate("notes/日記.md", "習慣", sentence = "昨日 習慣 を読んだ。", sentenceLink = 3..4)),
            emptyList(),
            current.ref,
            index
        )

        val row = backlinkRows(sources, emptyMap(), "習慣.md").single()

        assertEquals("昨日 このノート を読んだ。", row.sentence)
        assertEquals("このノート", row.sentence.substring(checkNotNull(row.sentenceLink)))
    }

    @Test
    fun `痕跡からは訪問の最新の日時と、見出しのあるメモの節だけを取る`() {
        val trace = ReadingTrace(
            vaultRelativePath = "notes/日記.md",
            noteTitle = "日記",
            documentId = null,
            visits = listOf(3_000L, 1_000L, 2_000L).map { ReadingVisit(it, deepestSectionTitle = null, progressPercent = 50) },
            memos = listOf(
                MarginMemo("a", 1L, sectionTitle = "まとめ"),
                MarginMemo("b", 2L, sectionTitle = null),
                MarginMemo("c", 3L, sectionTitle = "導入")
            )
        )

        assertEquals(
            SourceReading(lastReadAt = 3_000L, memoSectionKeys = setOf(headingMatchKey("まとめ"), headingMatchKey("導入"))),
            sourceReadingOf(trace)
        )
        assertEquals(null, sourceReadingOf(trace.copy(visits = emptyList(), totalVisitCount = 0)).lastReadAt)
    }

    @Test
    fun `出どころが走査の索引に無ければ解かずに除き、今のノート自身のリンクも除く`() {
        val index = NoteLinkIndex(listOf(current))

        val rows = rowsOf(
            listOf(candidate("locked/見えない.md", "習慣"), candidate("notes/習慣.md", "習慣")),
            emptyList(),
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

    /** 記号だけの見出しでも、索引の番目で開くとリンクのある節へ着く（数える側と探す側が同じ鍵を使う）。 */
    @Test
    fun `記号だけの見出しの2つ目にあるリンクも、索引の番目で開くと2つ目の見出しへ着く`() {
        val body = "冒頭。\n\n## ★\n\n本文。\n\n## ☆\n\n別の節。\n\n## ★\n\nここで [[習慣]] を書いた。\n"
        val link = extractIndexedLinks(body).single()
        val blocks = parseMarkdownBlocks(body)

        val opened = headingBlockIndex(blocks, checkNotNull(link.sectionHeading), link.sectionHeadingOccurrence)

        val stars = blocks.indices.filter { (blocks[it] as? MarkdownBlock.Heading)?.text == "★" }
        assertEquals(stars[1], opened)
    }

    @Test
    fun `パスで書いたリンクも出どころのフォルダから解く`() {
        val source = note("notes/sub/日記.md")
        val index = NoteLinkIndex(listOf(current, source))

        val rows = rowsOf(
            listOf(candidate("notes/sub/日記.md", "../習慣.md", kind = IndexedLinkKind.PATH)),
            emptyList(),
            current.ref,
            index
        )

        assertEquals(listOf("日記.md"), rows.map { it.note.title })
    }
}
