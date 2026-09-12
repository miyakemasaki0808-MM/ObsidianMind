package com.example.newproject.domain

import com.example.newproject.model.NoteField
import com.example.newproject.model.NoteExcerpt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 入力指紋が「AIへ実際に渡したもの」を表していることを固定する（→ 判断9）。
 *
 * **落ちなくなった項目は、そのまま「変えても再判定されない」バグになる。**
 * 語彙の版が最も気づきにくい — リストを更新しても古い結果が出続ける。
 */
class NoteFieldInputVersionTest {

    private val excerpt = NoteExcerpt("Kotlin の Flow について", isAbridged = false)

    @Test
    fun `同じ入力なら同じ指紋になる`() {
        assertEquals(
            noteFieldInputVersion("Flow.md", excerpt, NoteField.Technical),
            noteFieldInputVersion("Flow.md", excerpt, NoteField.Technical)
        )
    }

    @Test
    fun `本文が変われば指紋が変わる`() {
        assertNotEquals(
            noteFieldInputVersion("Flow.md", excerpt, NoteField.Technical),
            noteFieldInputVersion("Flow.md", NoteExcerpt("料理のメモ", isAbridged = false), NoteField.Technical)
        )
    }

    /**
     * **ヒントは入力の一部である。**
     *
     * 同じ本文を別フォルダへ複製すると別のヒントが付く。ここが落ちると、
     * **移動してヒントが変わっても古い結果が再利用される。**
     */
    @Test
    fun `ヒントが変われば指紋が変わる`() {
        val withHint = noteFieldInputVersion("Flow.md", excerpt, NoteField.Technical)
        val otherHint = noteFieldInputVersion("Flow.md", excerpt, NoteField.Learning)
        val noHint = noteFieldInputVersion("Flow.md", excerpt, null)

        assertNotEquals(withHint, otherHint)
        assertNotEquals(withHint, noHint)
        assertNotEquals(otherHint, noHint)
    }

    /**
     * **タイトルもプロンプトへ載る（P2-2）。**
     *
     * 落とすと、同じテンプレート本文を持つ異題ノートが同じ鍵へ畳まれ、
     * **先に開いたノートの分類が両方へ効く。**
     */
    @Test
    fun `タイトルが変われば指紋が変わる`() {
        assertNotEquals(
            noteFieldInputVersion("Kotlin.md", excerpt, null),
            noteFieldInputVersion("Dinner.md", excerpt, null)
        )
    }

    /** 省略の有無もプロンプトの中身を変える（注意書きが付く）。 */
    @Test
    fun `省略の有無で指紋が変わる`() {
        assertNotEquals(
            noteFieldInputVersion("Flow.md", excerpt, null),
            noteFieldInputVersion("Flow.md", NoteExcerpt(excerpt.text, isAbridged = true), null)
        )
    }
}
