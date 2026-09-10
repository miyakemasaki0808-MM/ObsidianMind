package com.example.newproject.domain

import com.example.newproject.model.NoteField
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分野判定の応答を読む規則を固定する。
 *
 * **「該当なし」と「読めなかった」が分かれていること**が要点（→ 判断13）。
 * 混ざると、正常な答えを再試行し続けるか、不正な答えを確定として永続するかになる。
 */
class NoteFieldAnswerTest {

    @Test
    fun `IDを1つ返せば選ばれた分野になる`() {
        assertEquals(NoteFieldAnswer.Chosen(NoteField.Technical), parseNoteFieldAnswer("F1"))
        assertEquals(NoteFieldAnswer.Chosen(NoteField.Reflection), parseNoteFieldAnswer("F6"))
    }

    @Test
    fun `NONE は正常な該当なしである`() {
        assertEquals(NoteFieldAnswer.NoneOfThem, parseNoteFieldAnswer("NONE"))
        assertEquals(NoteFieldAnswer.NoneOfThem, parseNoteFieldAnswer("none"))
    }

    /** 整形の癖は吸収する。**答えが1つに定まっていれば読む。** */
    @Test
    fun `整形の癖は吸収する`() {
        assertEquals(NoteFieldAnswer.Chosen(NoteField.Learning), parseNoteFieldAnswer("- F2"))
        assertEquals(NoteFieldAnswer.Chosen(NoteField.Learning), parseNoteFieldAnswer("\"F2\""))
        assertEquals(NoteFieldAnswer.Chosen(NoteField.Learning), parseNoteFieldAnswer("f2."))
        assertEquals(NoteFieldAnswer.Chosen(NoteField.Learning), parseNoteFieldAnswer("```\nF2\n```"))
        assertEquals(NoteFieldAnswer.Chosen(NoteField.Learning), parseNoteFieldAnswer("F2\nF2"))
    }

    /**
     * **1つに定まらなければ落とす。**
     *
     * 多数決も先頭優先もしない。どちらも根拠が無く、**確定として永続すると
     * 入力版が同じあいだ二度と直らない。** 落とせば次に開いたときやり直せる。
     */
    @Test
    fun `複数のIDは読めなかった扱いになる`() {
        assertEquals(NoteFieldAnswer.Invalid, parseNoteFieldAnswer("F1\nF3"))
        assertEquals(NoteFieldAnswer.Invalid, parseNoteFieldAnswer("F1\nNONE"))
    }

    @Test
    fun `空や未知のIDは読めなかった扱いになる`() {
        assertEquals(NoteFieldAnswer.Invalid, parseNoteFieldAnswer(""))
        assertEquals(NoteFieldAnswer.Invalid, parseNoteFieldAnswer("   \n  "))
        assertEquals(NoteFieldAnswer.Invalid, parseNoteFieldAnswer("F9"))
        assertEquals(NoteFieldAnswer.Invalid, parseNoteFieldAnswer("技術・開発"))
    }

    /**
     * **説明文の中のIDは拾わない。**
     *
     * 拾うと「F1 ではなく F3 が適切です」から誤ったIDを取り出す。
     * 行頭にIDが無い応答は、そもそも指示に従っていないので落としてよい。
     */
    @Test
    fun `説明文に紛れたIDは拾わない`() {
        assertEquals(NoteFieldAnswer.Invalid, parseNoteFieldAnswer("この note は F1 が適切です。"))
        assertEquals(NoteFieldAnswer.Invalid, parseNoteFieldAnswer("The answer is F1."))
    }

    /**
     * **同じ行の併記・否定文は落とす（P2-3）。**
     *
     * 先頭だけを見ると、曖昧な応答と**明示的に否定された分野**が確定として保存され、
     * 同じ入力版では再試行されない。
     */
    @Test
    fun `同じ行の複数候補と否定文は読めなかった扱いになる`() {
        assertEquals(NoteFieldAnswer.Invalid, parseNoteFieldAnswer("F1 / F3"))
        assertEquals(NoteFieldAnswer.Invalid, parseNoteFieldAnswer("F1 ではなく F3 が適切です"))
        assertEquals(NoteFieldAnswer.Invalid, parseNoteFieldAnswer("NONE / F1"))
        assertEquals(NoteFieldAnswer.Invalid, parseNoteFieldAnswer("F1 (技術・開発)"))
    }

    /** `F12` や `Field` を `F1` と読まない。 */
    @Test
    fun `IDに続きがあるものは弾く`() {
        assertEquals(NoteFieldAnswer.Invalid, parseNoteFieldAnswer("F12"))
        assertEquals(NoteFieldAnswer.Invalid, parseNoteFieldAnswer("Field"))
    }
}
