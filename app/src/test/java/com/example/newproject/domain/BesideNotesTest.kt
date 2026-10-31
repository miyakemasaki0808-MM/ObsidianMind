package com.example.newproject.domain

import com.example.newproject.data.ReadingTraceJson
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.model.NoteFile
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.BesideNote
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.ReadingVisit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 並べた相手の候補の規則（→ features/desk_memory.md §5・判断9）。
 *
 * **相対パスは変えずに外し、タイトルは整える。** ここを通った値は、v9 の検証を必ず通る（最後のテスト）。
 * 境は UTF-8 バイトで測る。
 */
class BesideNotesTest {

    @Test
    fun `相対パスは上限ちょうどまで候補にし、1バイト超えたら外す`() {
        val atLimit = "a".repeat(ReadingTraceLimits.MAX_RELATIVE_PATH_BYTES - 3) + ".md"
        val japaneseOver = "あ".repeat(ReadingTraceLimits.MAX_RELATIVE_PATH_BYTES / 3) + "aa"

        assertEquals(atLimit, composeBesideNote(atLimit, "t")?.vaultRelativePath)
        assertNull(composeBesideNote("a$atLimit", "t"))
        assertNull("日本語を含む1,025バイト", composeBesideNote(japaneseOver, "t"))
    }

    /** 切った・削った相対パスは別のノートを指しうるので、変えずに外す。 */
    @Test
    fun `引けない・空・制御文字を含む相対パスは変えずに外す`() {
        assertNull(composeBesideNote(null, "t"))
        assertNull(composeBesideNote(" ", "t"))
        assertNull(composeBesideNote("refs/\u0001相手.md", "t"))
        assertNull(composeBesideNote("refs/改\n行.md", "t"))
        assertNull(composeBesideNote("refs/C1\u0085.md", "t"))
    }

    @Test
    fun `タイトルは制御文字を落として上限で整え、切っても相手は残る`() {
        val over = "あ".repeat(ReadingTraceLimits.MAX_NOTE_TITLE_BYTES / 3) + "abc"

        val trimmed = composeBesideNote("refs/a.md", over)!!.title

        assertEquals(ReadingTraceLimits.MAX_NOTE_TITLE_BYTES, trimmed.toByteArray(Charsets.UTF_8).size)
        assertEquals("相手のノート", composeBesideNote("refs/a.md", " 相手の\u0007ノート\n ")!!.title)
    }

    /**
     * **文字の途中で切らない。** 絵文字を割ると、保存の往復で片割れが `?` に替わり、同じ読書の訪問を差し替えられずに増える。
     * ファイル名で補うときも同じ切り方をする。
     */
    @Test
    fun `タイトルは絵文字の途中で切らず、保存の往復で同じ値のまま`() {
        val cases = mapOf(
            "あ".repeat(170) + "😀" to "あ".repeat(170),
            "a".repeat(511) + "😀" to "a".repeat(511),
            "a".repeat(508) + "😀" to "a".repeat(508) + "😀"
        )

        cases.forEach { (title, expected) ->
            val beside = composeBesideNote("refs/a.md", title)!!
            assertEquals(expected, beside.title)
            assertEquals(title, beside, roundTrip(beside))
        }
        val fromFileName = composeBesideNote("refs/" + "あ".repeat(170) + "😀.md", null)!!
        assertEquals("あ".repeat(170), fromFileName.title)
        assertEquals(fromFileName, roundTrip(fromFileName))
    }

    @Test
    fun `タイトルが空ならファイル名を使い、それも空なら外す`() {
        assertEquals("習慣の科学", composeBesideNote("refs/習慣の科学.md", null)?.title)
        assertEquals("習慣の科学", composeBesideNote("refs/習慣の科学.md", "\u0001 ")?.title)
        assertNull(composeBesideNote("refs/.md", " "))
    }

    /** **候補を通った値は、v9 の検証を必ず通る。** 通らない値を渡すと、相手のせいで訪問とメモの保存が止まる。 */
    @Test
    fun `候補を通った相手は痕跡の検証を通る`() {
        val samples = listOf(
            "a".repeat(ReadingTraceLimits.MAX_RELATIVE_PATH_BYTES - 3) + ".md" to "あ".repeat(400),
            "refs/記号 \" \\ —…※.md" to "\u0001\u001F 記号 \" \\ —…※ \u0085",
            "refs/絵文字😀.md" to null
        )

        samples.forEach { (path, title) ->
            val beside = composeBesideNote(path, title) ?: error("候補にならなかった: $path")
            ReadingTraceJson.encode(traceWith(beside))
        }
    }

    /**
     * **控えた相対パスの完全一致だけで引く**（→ features/desk_memory.md §5）。本文のリンクのような同名への寄せはせず、
     * 索引のパス引きが大文字小文字を区別しなくても、表記が違えば見つからない。索引がまだ無ければ待ってもらう。
     */
    @Test
    fun `控えた相手は相対パスの完全一致だけで引く`() {
        val index = NoteLinkIndex(
            listOf(
                NoteFile(name = "右のノート", ref = DocumentRef("content://b"), vaultRelativePath = "refs/右のノート.md"),
                NoteFile(name = "Case", ref = DocumentRef("content://c"), vaultRelativePath = "refs/Case.md")
            )
        )

        val found = resolveBesideNote(BesideNote("refs/右のノート.md", "控えた名前"), index) as NoteLinkResolution.Open
        assertEquals(DocumentRef("content://b"), found.note.ref)
        assertEquals("右のノート", found.note.title)
        assertNull(found.heading)
        assertEquals(NoteLinkResolution.Missing("控えた名前"), resolveBesideNote(BesideNote("右のノート.md", "控えた名前"), index))
        assertEquals(NoteLinkResolution.Missing("C"), resolveBesideNote(BesideNote("refs/case.md", "C"), index))
        assertEquals(NoteLinkResolution.NotReady, resolveBesideNote(BesideNote("refs/右のノート.md", "B"), NoteLinkIndex.EMPTY))
    }

    private fun roundTrip(beside: BesideNote): BesideNote? =
        (ReadingTraceJson.decode(ReadingTraceJson.encode(traceWith(beside))) as ReadingTraceReadResult.Valid).trace.visits.single().beside

    private fun traceWith(beside: BesideNote) = ReadingTrace(
        vaultRelativePath = "ideas/habit.md",
        noteTitle = "習慣について",
        documentId = null,
        visits = listOf(ReadingVisit(1_000L, null, 50, beside))
    )
}
