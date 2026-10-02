package com.example.newproject.domain

import com.example.newproject.model.MarginMemo
import com.example.newproject.model.MemoFileRead
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.MarginMemoDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 余白メモの書きかけと送信の規則（→ features/margin_pane.md §6.1・§6.2・§10）。
 *
 * **1回の送信で1件だけ保存され、未保存と断定しない**ことを、純関数の段で固定する。
 */
class MarginMemoDraftRulesTest {

    private val sectionB = SectionRef("節B")
    private val sectionC = SectionRef("節C")

    // ── 書き込み先 ─────────────────────────────────────────────────────────

    @Test
    fun `書き始めた節を書き込み先にし、文字がある間は本文が進んでも動かさない`() {
        val draft = MarginMemoDraft()
            .edited("書", bodySection = sectionB)
            .edited("書き足す", bodySection = sectionC)

        assertEquals(sectionB, draft.target)
    }

    @Test
    fun `入力を空にしたら書き込み先を放し、次に書き始めた節を取る`() {
        val draft = MarginMemoDraft()
            .edited("書", bodySection = sectionB)
            .edited("", bodySection = sectionB)

        assertNull(draft.target)
        assertEquals(sectionC, draft.edited("次", bodySection = sectionC).target)
    }

    /** 解析の前は節が分からない。決めずに置き、分かった時点の入力で決める。 */
    @Test
    fun `本文の節が分からないうちは決めず、分かった入力で決める`() {
        val draft = MarginMemoDraft().edited("書", bodySection = null)
        assertNull(draft.target)

        assertEquals(sectionC, draft.edited("書き", bodySection = sectionC).target)
    }

    // ── 送信を作る ─────────────────────────────────────────────────────────

    @Test
    fun `空白だけの入力からは送信を作らない`() {
        assertNull(submissionOf(" \n\t ", sectionTitle = null, nowEpochMillis = 1L))
    }

    /** **整えるのは送信を作るときの1回だけ。** 保存値・時刻・切り詰めがここで決まる。 */
    @Test
    fun `送信は整えた保存値と時刻と書き込み先の見出しを持つ`() {
        val submission = submissionOf("  前後の空白\n\n", sectionTitle = "  節B\u0007 ", nowEpochMillis = 42L)!!

        assertEquals("  前後の空白\n\n", submission.raw)
        assertEquals(MarginMemo("前後の空白", 42L, "節B"), submission.memo)
        assertEquals(false, submission.wasTruncated)
    }

    @Test
    fun `1024バイトを超える日本語は切り、切ったことを送信が持つ`() {
        val raw = "あ".repeat(ReadingTraceLimits.MAX_MEMO_BYTES)
        val submission = submissionOf(raw, sectionTitle = null, nowEpochMillis = 1L)!!

        assertTrue(submission.wasTruncated)
        assertTrue(submission.memo.text.toByteArray(Charsets.UTF_8).size <= ReadingTraceLimits.MAX_MEMO_BYTES)
    }

    // ── 照合 ───────────────────────────────────────────────────────────────

    @Test
    fun `一覧に保存値と時刻の組があれば受理`() {
        val submission = submissionOf("メモ ", null, 10L)!!

        assertEquals(
            SubmissionCheck.Accepted,
            checkSubmission(submission, listOf(MarginMemo("メモ", 10L)), MemoFileRead.Unconfirmed)
        )
    }

    /** **本文がたまたま同じ別のメモと取り違えない。** 鍵は時刻との組。 */
    @Test
    fun `本文が同じでも時刻が違えば同じ送信とみなさない`() {
        val submission = submissionOf("同じ文", null, 10L)!!

        assertEquals(
            SubmissionCheck.NotAccepted,
            checkSubmission(submission, listOf(MarginMemo("同じ文", 9L)), MemoFileRead.Read)
        )
    }

    /** 一覧に無いことを未受理と読んでよいのは、読めたか無いと確かめたときだけ（→ lessons L47）。 */
    @Test
    fun `一覧に無いときは読み込みの確かさで未受理と確認できないを分ける`() {
        val submission = submissionOf("メモ", null, 10L)!!

        assertEquals(SubmissionCheck.NotAccepted, checkSubmission(submission, emptyList(), MemoFileRead.Read))
        assertEquals(
            SubmissionCheck.NotAccepted,
            checkSubmission(submission, emptyList(), MemoFileRead.ConfirmedAbsent)
        )
        assertEquals(
            SubmissionCheck.Unconfirmed,
            checkSubmission(submission, emptyList(), MemoFileRead.Unconfirmed)
        )
    }

    // ── 受理・未受理 ─────────────────────────────────────────────────────────

    @Test
    fun `受理されたら送信を消費し、原文のままの入力を空にして書き込み先を放す`() {
        val submission = submissionOf("送るメモ\n", null, 10L)!!
        val draft = MarginMemoDraft().edited("送るメモ\n", sectionB).submitted(submission)

        val after = draft.settled(submission, SubmissionCheck.Accepted)

        assertEquals(MarginMemoDraft(), after)
        assertTrue(after.isEmpty)
    }

    @Test
    fun `待っている間に書き直した文字は、受理されても残す`() {
        val submission = submissionOf("1件目", null, 10L)!!
        val draft = MarginMemoDraft().edited("1件目", sectionB).submitted(submission)
            .edited("待っているあいだに書いた", sectionC)

        val after = draft.settled(submission, SubmissionCheck.Accepted)

        assertEquals("待っているあいだに書いた", after.text)
        assertEquals(sectionB, after.target)
        assertNull(after.pending)
    }

    @Test
    fun `未受理なら送信を取り下げ、原文を残す`() {
        val submission = submissionOf("置けなかった", null, 10L)!!
        val draft = MarginMemoDraft().edited("置けなかった", sectionB).submitted(submission)

        val after = draft.settled(submission, SubmissionCheck.NotAccepted)

        assertEquals("置けなかった", after.text)
        assertEquals(sectionB, after.target)
        assertNull(after.pending)
    }

    @Test
    fun `確認できないなら送信も原文も持ち続ける`() {
        val submission = submissionOf("確かめられない", null, 10L)!!
        val draft = MarginMemoDraft().edited("確かめられない", sectionB).submitted(submission)

        assertEquals(draft, draft.settled(submission, SubmissionCheck.Unconfirmed))
    }

    /** **追わなくなった古い送信の結果は、新しい入力を消さない。** */
    @Test
    fun `新しく送った後に古い送信の受理が届いても何も消さない`() {
        val old = submissionOf("古い", null, 10L)!!
        val new = submissionOf("新しい", null, 20L)!!
        val draft = MarginMemoDraft().edited("古い", sectionB).submitted(old)
            .edited("新しい", sectionB).submitted(new)

        assertEquals(draft, draft.settled(old, SubmissionCheck.Accepted))
        assertEquals(draft, draft.settled(old, SubmissionCheck.NotAccepted))
    }

    // ── ボタンの役目 ─────────────────────────────────────────────────────────

    /**
     * **未確定の間の編集で、同じ本文をもう1件送らない。** 末尾の空白・末尾の改行・
     * 1024バイトより後ろだけの編集・編集して戻す、のどれも整えた本文は同じになる。
     */
    @Test
    fun `未確定の間に整えた本文が変わらない編集をしても、確かめる役目のまま`() {
        val raw = "未確定のメモ"
        val submission = submissionOf(raw, null, 10L)!!
        val draft = MarginMemoDraft().edited(raw, sectionB).submitted(submission)

        listOf("$raw ", "$raw\n", raw).forEach { edited ->
            assertEquals("「$edited」", MemoSendAction.Verify, draft.edited(edited, sectionB).sendAction())
        }
        val reverted = draft.edited("未確定のメ", sectionB).edited(raw, sectionB)
        assertEquals(MemoSendAction.Verify, reverted.sendAction())

        val long = "あ".repeat(ReadingTraceLimits.MAX_MEMO_BYTES)
        val longSubmission = submissionOf(long, null, 30L)!!
        val longDraft = MarginMemoDraft().edited(long, sectionB).submitted(longSubmission)
        assertEquals(
            "1024バイトより後ろだけの編集で別のメモとして送った",
            MemoSendAction.Verify,
            longDraft.edited(long + "後ろだけ足す", sectionB).sendAction()
        )
    }

    @Test
    fun `整えた本文が変われば新しいメモとして置く`() {
        val submission = submissionOf("未確定のメモ", null, 10L)!!
        val draft = MarginMemoDraft().edited("未確定のメモ", sectionB).submitted(submission)

        assertEquals(MemoSendAction.Place, draft.edited("別の内容", sectionB).sendAction())
        assertEquals(MemoSendAction.Place, MarginMemoDraft().edited("初めて", sectionB).sendAction())
        assertEquals(MemoSendAction.None, MarginMemoDraft().edited("  \n", sectionB).sendAction())
    }

    @Test
    fun `未確定の送信は入力を消しても持ち続ける`() {
        val submission = submissionOf("未確定のメモ", null, 10L)!!
        val draft = MarginMemoDraft().edited("未確定のメモ", sectionB).submitted(submission).edited("", sectionB)

        assertNotNull(draft.pending)
        assertEquals(false, draft.isEmpty)
    }
}
