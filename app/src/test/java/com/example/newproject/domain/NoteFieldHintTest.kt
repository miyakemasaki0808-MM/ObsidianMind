package com.example.newproject.domain

import com.example.newproject.model.NoteField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * パス→分野のヒント生成を固定する。
 *
 * **「当たらない」を失敗として書かない。** 採番コードだけのVaultではヒントが作れないのが
 * 仕様であり（→ `docs/dev/features/note_field_color.md` 判断12 の3例）、
 * ここではその3例が**実際に区別される**ことを見る。
 */
class NoteFieldHintTest {

    @Test
    fun `意味のあるフォルダ名は当たる`() {
        assertEquals(NoteField.Learning, noteFieldHint("資格試験/簿記/仕訳.md"))
        assertEquals(NoteField.Living, noteFieldHint("料理/カレー.md"))
        assertEquals(NoteField.Technical, noteFieldHint("Development/Kotlin/Flow.md"))
        assertEquals(NoteField.Reflection, noteFieldHint("journal/2026-09-10.md"))
    }

    @Test
    fun `採番コードだけのフォルダ名は当たらない`() {
        assertNull(noteFieldHint("_Reference_FileData/0500_000F/0500_000F_B006.md"))
        assertNull(noteFieldHint("0500_0015/0500_0015_B001_Layouts.md"))
    }

    @Test
    fun `フラットなVaultはファイル名で照合する`() {
        // フォルダを持たないVaultでも、ファイル名が意味を持つなら当たってよい。
        assertEquals(NoteField.Living, noteFieldHint("今週の献立.md"))
        assertNull(noteFieldHint("0000.Index.md"))
    }

    /**
     * **パスの浅い側が勝つ。**
     *
     * 試験のために読んでいる Python の教材は、技術ではなく学習である。
     * ここが逆になると、資格試験フォルダの中身が全部「技術・開発」で塗られる。
     */
    @Test
    fun `浅い側のフォルダが深い側より優先される`() {
        assertEquals(NoteField.Learning, noteFieldHint("試験対策/Python_Development/例外.md"))
        // 逆に、浅い側が当たらなければ深い側を見る。
        assertEquals(NoteField.Technical, noteFieldHint("_Reference/Development/例外.md"))
    }

    /**
     * **同じ深さで複数当たったら宣言順。**
     *
     * 規則を決めておかないと、辞書へ語を足すたびに既存ノートの色が入れ替わる。
     */
    @Test
    fun `同じ深さで複数当たったら宣言順で決まる`() {
        // Technical が Learning より先に宣言されている。
        assertEquals(NoteField.Technical, noteFieldHint("開発と学習.md"))
    }

    /**
     * **英字は完全一致でしか当たらない。**
     *
     * 部分一致にすると `art` が `article` に当たる。実際にオーナーのVaultでは
     * `_NumberData_Folder_and_article` 配下の206本が誤ったヒントを持った。
     */
    @Test
    fun `英字の部分一致では当たらない`() {
        assertNull(noteFieldHint("_NumberData_Folder_and_article/0F00.md"))
        assertNull(noteFieldHint("particle/start/department.md"))
        // 完全一致なら当たる。
        assertEquals(NoteField.Creative, noteFieldHint("art/sketch.md"))
    }

    /** 日本語は語の切れ目が無いので、トークンの内側を見る。 */
    @Test
    fun `日本語はトークンの内側でも当たる`() {
        assertEquals(NoteField.Learning, noteFieldHint("FF.試験クリア/合格.md"))
    }

    @Test
    fun `空のパスは当たらない`() {
        assertNull(noteFieldHint(""))
        assertNull(noteFieldHint("   "))
        assertNull(noteFieldHint("///"))
    }
}
