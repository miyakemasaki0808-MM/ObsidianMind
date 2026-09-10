package com.example.newproject.domain

import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteField
import com.example.newproject.model.NoteFieldClassification
import com.example.newproject.model.NoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 索引A へヒントを流し込む規則を固定する（→ `docs/dev/features/note_field_color.md` 判断16）。
 *
 * **走査は初回だけではない**（`collectAllNotesCached` はTTLで走り直す）ので、
 * ここが素通しだと**再走査のたびに色がAI確定からヒントへ戻る**。
 */
class NoteFieldIndexTest {

    private fun note(path: String, ref: String = path) =
        NoteFile(name = path.substringAfterLast('/'), ref = DocumentRef(ref), vaultRelativePath = path)

    @Test
    fun `ヒントが作れるノートは暫定として載る`() {
        val index = indexNoteFieldHints(emptyMap(), listOf(note("料理/カレー.md")))

        assertEquals(
            NoteFieldClassification.Provisional(NoteField.Living),
            index[DocumentRef("料理/カレー.md")]
        )
    }

    @Test
    fun `ヒントが作れないノートは載せない`() {
        val index = indexNoteFieldHints(emptyMap(), listOf(note("0500_000F/0500_000F_B006.md")))

        assertTrue("未判定は明示的に置かない（引けないことが未判定である）", index.isEmpty())
    }

    /**
     * **確定は暫定で上書きしない。**
     *
     * ここが落ちると、AIが直した分野が**次の再走査でヒントへ戻る**。
     * 判断6（AIで補正する）が走査に負けることになるので、機能の狙いがそこで消える。
     */
    @Test
    fun `再走査しても確定は残る`() {
        val ref = DocumentRef("料理/カレー.md")
        val confirmed = NoteFieldClassification.Confirmed(NoteField.Technical, inputVersion = "v1")

        val index = indexNoteFieldHints(mapOf(ref to confirmed), listOf(note("料理/カレー.md")))

        assertEquals("ヒントは Living だが、確定した Technical が勝つ", confirmed, index[ref])
    }

    /** 確定が「該当なし」でも同じ。**無彩色であることと未判定であることは違う。** */
    @Test
    fun `該当なしの確定も暫定で上書きされない`() {
        val ref = DocumentRef("料理/カレー.md")
        val none = NoteFieldClassification.Confirmed(field = null, inputVersion = "v1")

        val index = indexNoteFieldHints(mapOf(ref to none), listOf(note("料理/カレー.md")))

        assertEquals(none, index[ref])
    }

    /** 暫定どうしは置き換わってよい。**辞書を直したら色が追いつく**のが正しい。 */
    @Test
    fun `暫定は新しいヒントで置き換わる`() {
        val ref = DocumentRef("n")
        val old = NoteFieldClassification.Provisional(NoteField.Business)

        val index = indexNoteFieldHints(mapOf(ref to old), listOf(note("料理/カレー.md", ref = "n")))

        assertEquals(NoteFieldClassification.Provisional(NoteField.Living), index[ref])
    }

    /**
     * **走査に居なくなったノートは落とす。**
     *
     * 抱え続けると、相対パスを付け替えた別のノートへ古い分類が当たる余地が残る。
     */
    @Test
    fun `走査に居ないノートは索引から消える`() {
        val gone = DocumentRef("消えた")
        val current = mapOf(
            gone to NoteFieldClassification.Confirmed(NoteField.Technical, "v1"),
            DocumentRef("料理/カレー.md") to NoteFieldClassification.Provisional(NoteField.Living)
        )

        val index = indexNoteFieldHints(current, listOf(note("料理/カレー.md")))

        assertNull("確定であっても、走査に居なければ残さない", index[gone])
        assertFalse(index.containsKey(gone))
        assertEquals(1, index.size)
    }

    /**
     * **一括復元は補完だけ**（→ 判断16）。
     *
     * メモリに確定があるなら、それは今回の個別経路が書いたもの＝必ず新しい。
     * ここが落ちると、**古い永続の確定が新しいAI確定を上書きする**。
     */
    @Test
    fun `永続からの復元はメモリの確定を上書きしない`() {
        val ref = DocumentRef("料理/カレー.md")
        val fresh = NoteFieldClassification.Confirmed(NoteField.Technical, inputVersion = "new")
        val stale = NoteFieldClassification.Confirmed(NoteField.Business, inputVersion = "old")

        val index = indexNoteFieldHints(
            current = mapOf(ref to fresh),
            notes = listOf(note("料理/カレー.md")),
            restored = mapOf(noteFieldPathKey("料理/カレー.md") to stale)
        )

        assertEquals(fresh, index[ref])
    }

    /** 確定が無いノートには届く。**補完は効く。** */
    @Test
    fun `確定が無いノートには永続の復元が届く`() {
        val ref = DocumentRef("料理/カレー.md")
        val stored = NoteFieldClassification.Confirmed(NoteField.Business, inputVersion = "v1")

        val index = indexNoteFieldHints(
            current = emptyMap(),
            notes = listOf(note("料理/カレー.md")),
            restored = mapOf(noteFieldPathKey("料理/カレー.md") to stored)
        )

        assertEquals("ヒントは Living だが、復元した確定が勝つ", stored, index[ref])
    }

    @Test
    fun `空の走査は索引を空にする`() {
        val current = mapOf(DocumentRef("n") to NoteFieldClassification.Provisional(NoteField.Living))

        assertTrue(indexNoteFieldHints(current, emptyList()).isEmpty())
    }
}
