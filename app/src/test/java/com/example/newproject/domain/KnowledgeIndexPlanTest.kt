package com.example.newproject.domain

import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteScanKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 走査の結果と知識索引の突き合わせ（→ docs/dev/system/knowledge_index.md §3）。
 *
 * **「日時が同じ」と「変わっていないと確かめた」を分ける。** 日時を返さないプロバイダと、アプリの書き換えで読み直しが漏れないこと。
 */
class KnowledgeIndexPlanTest {

    private fun note(path: String, lastModified: Long?) =
        NoteFile(name = path.substringAfterLast('/'), ref = DocumentRef("ref:$path"), lastModified = lastModified, vaultRelativePath = path)

    private fun indexed(lastModified: Long?) = IndexedNoteState(lastModified, fingerprint = "f")

    @Test
    fun `索引に無いノートと、更新日時が違うノートを読み、同じノートは読まない`() {
        val plan = planReconcile(
            notes = listOf(note("new.md", 1L), note("changed.md", 2L), note("same.md", 3L)),
            kind = NoteScanKind.COMPLETE,
            indexed = mapOf("changed.md" to indexed(1L), "same.md" to indexed(3L)),
            rewritten = emptySet()
        )

        assertEquals(listOf("new.md", "changed.md"), plan.toRead.map { it.vaultRelativePath })
    }

    @Test
    fun `更新日時が走査でも索引でも空なら、変わっていないと読めないので毎回読む`() {
        val plan = planReconcile(
            notes = listOf(note("nodate.md", null), note("dated.md", 5L)),
            kind = NoteScanKind.COMPLETE,
            indexed = mapOf("nodate.md" to indexed(null), "dated.md" to indexed(5L)),
            rewritten = emptySet()
        )

        assertEquals(listOf("nodate.md"), plan.toRead.map { it.vaultRelativePath })
    }

    @Test
    fun `アプリが書き換えたと控えてあれば、日時が同じでも読む`() {
        val plan = planReconcile(
            notes = listOf(note("written.md", 7L)),
            kind = NoteScanKind.COMPLETE,
            indexed = mapOf("written.md" to indexed(7L)),
            rewritten = setOf("ref:written.md")
        )

        assertEquals(listOf("written.md"), plan.toRead.map { it.vaultRelativePath })
    }

    @Test
    fun `読み切れた走査に無いノートだけを消し、一部を読めない走査では消さない`() {
        val indexedNotes = mapOf("kept.md" to indexed(1L), "gone.md" to indexed(1L))
        val notes = listOf(note("kept.md", 1L))

        assertEquals(listOf("gone.md"), planReconcile(notes, NoteScanKind.COMPLETE, indexedNotes, emptySet()).toDelete)
        assertEquals(emptyList<String>(), planReconcile(notes, NoteScanKind.PARTIAL, indexedNotes, emptySet()).toDelete)
    }

    @Test
    fun `読み切った空の Vault では索引を全部消す`() {
        val plan = planReconcile(emptyList(), NoteScanKind.COMPLETE, mapOf("a.md" to indexed(1L), "b.md" to indexed(2L)), emptySet())

        assertEquals(listOf("a.md", "b.md"), plan.toDelete)
    }

    @Test
    fun `読めずに空の走査では何も読まず何も消さない`() {
        val plan = planReconcile(emptyList(), NoteScanKind.UNREADABLE_EMPTY, mapOf("a.md" to indexed(1L)), setOf("ref:a.md"))

        assertEquals(ReconcilePlan.NONE, plan)
    }

    @Test
    fun `相対パスを持たないノートは扱わない`() {
        val plan = planReconcile(listOf(note("", 1L)), NoteScanKind.COMPLETE, emptyMap(), emptySet())

        assertTrue(plan.toRead.isEmpty())
    }

    /** 控えは参照で持つ。読み切れた走査に無い参照（消えたノート・前の Vault の参照）の控えは、もう読むことが無いので外す。 */
    @Test
    fun `読み切れた走査に無い参照の控えだけを外し、一部を読めない走査では外さない`() {
        val notes = listOf(note("kept.md", 1L))
        val rewritten = setOf("ref:kept.md", "ref:gone.md", "other-vault:a.md")

        assertEquals(
            listOf("other-vault:a.md", "ref:gone.md"),
            planReconcile(notes, NoteScanKind.COMPLETE, emptyMap(), rewritten).forgetMarks
        )
        assertEquals(emptyList<String>(), planReconcile(notes, NoteScanKind.PARTIAL, emptyMap(), rewritten).forgetMarks)
    }

    @Test
    fun `読んだ結果を書けるのは、読み始めたときの書き換えの番号が今も最新のときだけ`() {
        assertTrue("控えが無いまま", mayApplyIndexedRead(null, null))
        assertTrue("同じ番号", mayApplyIndexedRead(3L, 3L))
        assertFalse("読んでいる途中に書き換えた", mayApplyIndexedRead(null, 4L))
        assertFalse("読んでいる途中にもう一度書き換えた", mayApplyIndexedRead(3L, 4L))
    }
}
