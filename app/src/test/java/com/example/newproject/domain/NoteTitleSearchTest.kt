package com.example.newproject.domain

import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTitleSearchTest {

    @Test
    fun `完全一致、前方一致、部分一致の順に並ぶ`() {
        // 部分一致の「あの習慣」はタイトルの順なら先に来るので、段が順序を決めていることまで見える。
        val notes = listOf(note("あの習慣.md"), note("習慣化.md"), note("習慣.md"), note("関係ない.md"))

        assertEquals(listOf("習慣.md", "習慣化.md", "あの習慣.md"), titles("習慣", notes))
    }

    @Test
    fun `同じ段の中はタイトルの順に並ぶ`() {
        val notes = listOf(note("b習慣.md"), note("a習慣.md"), note("c習慣.md"))

        assertEquals(listOf("a習慣.md", "b習慣.md", "c習慣.md"), titles("習慣", notes))
    }

    @Test
    fun `大文字小文字と空白の違いを無視する`() {
        val notes = listOf(note("Deep Work メモ.md"))

        assertEquals(1, titles("deepwork", notes).size)
        assertEquals(1, titles("DEEP WORK　メモ", notes).size)
    }

    @Test
    fun `3件に限らず全部を返す`() {
        val notes = (1..50).map { note("習慣$it.md") }

        assertEquals(50, titles("習慣", notes).size)
    }

    @Test
    fun `拡張子は照らさないので、mdと打って全ノートが出ない`() {
        val notes = listOf(note("習慣.md"), note("読書.MD"), note("mdの書き方.md"))

        assertEquals(listOf("mdの書き方.md"), titles("md", notes))
    }

    @Test
    fun `画面に出る拡張子つきの名前を打っても完全一致で出る`() {
        val notes = listOf(note("習慣化.md"), note("習慣.md"))

        assertEquals(listOf("習慣.md", "習慣化.md"), titles("習慣.md", notes))
    }

    @Test
    fun `本文やパスのフォルダ名は照らさない`() {
        val notes = listOf(note("習慣/読書.md"))

        assertTrue(titles("習慣", notes).isEmpty())
    }

    @Test
    fun `空白だけなら何も返さない`() {
        assertTrue(matchNotesByTitle("  　", listOf(note("習慣.md"))).isEmpty())
    }

    @Test
    fun `同じ名前のノートにだけフォルダの場所を添え、別々の行のまま場所の順に並ぶ`() {
        val notes = listOf(note("ideas/2026/メモ.md"), note("メモ.md"), note("books/メモ.md"), note("ideas/別のメモ.md"))

        val matches = matchNotesByTitle("メモ", notes)

        assertEquals(
            listOf("books", "ideas/2026", "", null),
            matches.map { it.folderPath }
        )
    }

    @Test
    fun `大文字小文字だけが違う名前は見分けられるので場所を添えない`() {
        val matches = matchNotesByTitle("note", listOf(note("Note.md"), note("note.md")))

        assertNull(matches[0].folderPath)
        assertNull(matches[1].folderPath)
    }

    @Test
    fun `結果は開くのに要る参照と更新日時を持つ`() {
        val file = NoteFile("習慣.md", DocumentRef("ref"), lastModified = 42L, vaultRelativePath = "習慣.md")

        val related = matchNotesByTitle("習慣", listOf(file)).single().note

        assertEquals(DocumentRef("ref"), related.ref)
        assertEquals(42L, related.lastModified)
    }

    @Test
    fun `フォルダの中は奥まで含み、名前が前方で重なる別のフォルダと直下を含まない`() {
        val notes = listOf(note("ideas/a.md"), note("ideas/2026/b.md"), note("ideas2/c.md"), note("ideas.md"))

        val inFolder = notesInFolder(notes, NoteFolder("ideas", "doc"))

        assertEquals(listOf("ideas/a.md", "ideas/2026/b.md"), inFolder.map { it.vaultRelativePath })
    }

    private fun titles(query: String, notes: List<NoteFile>): List<String> =
        matchNotesByTitle(query, notes).map { it.note.title }

    private fun note(path: String) = NoteFile(
        name = path.substringAfterLast('/'),
        ref = DocumentRef("content://fake/$path"),
        vaultRelativePath = path
    )
}
