package com.example.newproject.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 余白メモの書きかけ（→ features/margin_pane.md §11 段1）。
 *
 * **表示部品が無い間に起きたことを、次に組み立てたときの照合だけで正しく畳めるか**を見る。
 * 照合は「前に見た値」を持たない復元の経路でも同じに働く必要がある。
 */
class MarginMemoInputTest {

    private val noteA = "content://vault-a/a.md"
    private val noteB = "content://vault-b/b.md"

    @Test
    fun `同じノートへ戻したときは書きかけを保つ`() {
        val input = MarginMemoInput(noteA).edited("Aに書きかけ")

        assertEquals("Aに書きかけ", input.reconciled(noteA, acceptedCount = 0).text)
    }

    /** 画面が無い間にノートやVaultが替わっても、**復元した値だけで**前のノートの文字と分かる。 */
    @Test
    fun `別のノートで照合すると前のノートの書きかけを捨てる`() {
        val restored = MarginMemoInput(noteA).edited("Aにだけ書きかけたメモ").submitting("Aにだけ書きかけたメモ")

        val inB = restored.reconciled(noteB, acceptedCount = 3)

        assertEquals("", inB.text)
        assertNull(inB.submitted)
        assertEquals(noteB, inB.noteKey)
        assertEquals("Bの受理件数を基準にする", 3L, inB.seenAcceptedCount)
    }

    @Test
    fun `読み込み中を挟むと書きかけを捨てる`() {
        val input = MarginMemoInput(noteA).edited("Aに書きかけ")

        assertEquals("", input.reconciled(null, acceptedCount = 0).reconciled(noteA, acceptedCount = 0).text)
    }

    /** **受理を見たのが、表示部品を作り直した後でも消費する。** 部品の中に持っていたときはここで残った。 */
    @Test
    fun `送った原文のままなら、受理を後から見ても空にする`() {
        val sent = MarginMemoInput(noteA).edited("送信するメモ").submitting("送信するメモ")

        val after = sent.reconciled(noteA, acceptedCount = 1)

        assertEquals("", after.text)
        assertNull(after.submitted)
        // 同じ受理をもう一度見ても何も起きない
        assertEquals(after, after.reconciled(noteA, acceptedCount = 1))
    }

    @Test
    fun `待っている間に書き直した文字は受理されても残す`() {
        val sent = MarginMemoInput(noteA).edited("1件目").submitting("1件目").edited("待っているあいだに書いた")

        assertEquals("待っているあいだに書いた", sent.reconciled(noteA, acceptedCount = 1).text)
    }

    /** 満杯・失敗では受理件数が増えない。原文を残し、押し直せば同じ文をもう一度送れる。 */
    @Test
    fun `受理されなければ原文を残す`() {
        val sent = MarginMemoInput(noteA).edited("あふれる断片").submitting("あふれる断片")

        val after = sent.reconciled(noteA, acceptedCount = 0)

        assertEquals("あふれる断片", after.text)
        assertEquals("あふれる断片", after.submitted)
    }

    /** 読めなかった後の読み直しで件数は0へ戻る。**戻っただけで消さず、その後の増加で消す。** */
    @Test
    fun `件数が戻ったときは消さず、そこからの増加で消す`() {
        val sent = MarginMemoInput(noteA, seenAcceptedCount = 2).edited("送信するメモ").submitting("送信するメモ")

        val reset = sent.reconciled(noteA, acceptedCount = 0)
        assertEquals("送信するメモ", reset.text)
        assertEquals(0L, reset.seenAcceptedCount)

        assertEquals("", reset.reconciled(noteA, acceptedCount = 1).text)
    }
}
