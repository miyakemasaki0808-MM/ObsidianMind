package com.example.newproject.domain

import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.MemoFileRead
import com.example.newproject.model.MemoPassage
import com.example.newproject.model.QuoteContext
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.DraftQuote
import com.example.newproject.model.state.MarginMemoDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ── 引用と控えた位置（→ features/margin_quote.md §5.3・§5.4）──────────────────

    private val quoteA = DraftQuote(MemoPassage.Quoted("一節A", QuoteContext("前", "後"), truncated = false))
    private val quoteB = DraftQuote(MemoPassage.Quoted("一節B", context = null, truncated = false))
    private val seenTop = MemoPassage.Seen("最上端の段落")

    @Test
    fun `書き始めた瞬間に、書き込み先と一緒に読んでいた位置を控える`() {
        val draft = MarginMemoDraft()
            .edited("書", bodySection = sectionB, seenAtStart = seenTop)
            .edited("書き足す", bodySection = sectionC, seenAtStart = MemoPassage.Seen("後で見えた段落"))

        assertEquals(seenTop, draft.seen)
        assertEquals(seenTop, draft.passage)
    }

    @Test
    fun `空にしたら控えた位置も放す`() {
        val draft = MarginMemoDraft().edited("書", sectionB, seenTop).edited("", sectionB, seenTop)

        assertNull(draft.seen)
    }

    @Test
    fun `引用が付いていれば書き始めても位置を控えない`() {
        val draft = MarginMemoDraft().quoted(quoteA, sectionC).edited("書", bodySection = sectionB, seenAtStart = seenTop)

        assertNull(draft.seen)
        assertEquals(quoteA.passage, draft.passage)
    }

    /** **書いている途中でも、引用は書き込み先を引用元の節へ移す**（スクロールでは動かない書き込み先とは別の、明示の操作）。 */
    @Test
    fun `引用を付けると書いている途中でも書き込み先が引用元の節へ移る`() {
        val draft = MarginMemoDraft().edited("書いていた", bodySection = sectionB).quoted(quoteA, sectionC)

        assertEquals(sectionC, draft.target)
        assertEquals("書いていた", draft.text)
    }

    @Test
    fun `引用元が決まらなければ書き込み先はそのまま`() {
        val draft = MarginMemoDraft().edited("書いていた", bodySection = sectionB).quoted(quoteB, section = null)

        assertEquals(sectionB, draft.target)
    }

    @Test
    fun `もう一度引用すると入れ替わり、書いた文字は残る`() {
        val draft = MarginMemoDraft().quoted(quoteA, sectionB).edited("添える言葉", sectionB).quoted(quoteB, sectionC)

        assertEquals(quoteB, draft.quote)
        assertEquals("添える言葉", draft.text)
    }

    /** **引用だけの書きかけは空ではない** — 窓の変化やノートの行き来で預かる。 */
    @Test
    fun `引用だけの書きかけは空ではなく、文字を空にしても書き込み先を保つ`() {
        val draft = MarginMemoDraft().quoted(quoteA, sectionC).edited("書", sectionB).edited("", sectionB)

        assertFalse(draft.isEmpty)
        assertEquals(sectionC, draft.target)
    }

    @Test
    fun `引用を外すと引用だけが消え、文字があれば書き込み先を保つ`() {
        val withText = MarginMemoDraft().quoted(quoteA, sectionC).edited("添える言葉", sectionB).unquoted()
        val onlyQuote = MarginMemoDraft().quoted(quoteA, sectionC).unquoted()

        assertNull(withText.quote)
        assertEquals("添える言葉", withText.text)
        assertEquals(sectionC, withText.target)
        assertTrue(onlyQuote.isEmpty)
        assertNull(onlyQuote.target)
    }

    /** **引用だけでは置かない。** 自分の言葉が要る（→ 判断2）。 */
    @Test
    fun `引用だけの書きかけは置けない`() {
        assertEquals(MemoSendAction.None, MarginMemoDraft().quoted(quoteA, sectionB).sendAction())
        assertEquals(MemoSendAction.Place, MarginMemoDraft().quoted(quoteA, sectionB).edited("添える", sectionB).sendAction())
    }

    @Test
    fun `送信は引用か控えた位置を運ぶ`() {
        val withQuote = MarginMemoDraft().quoted(quoteA, sectionB).edited("添える", sectionB)
        val submission = submissionOf(withQuote.text, sectionB.title, 1_000L, withQuote.passage)!!

        assertEquals(quoteA.passage, submission.memo.passage)
        assertEquals(null, submissionOf("添える", null, 1_000L)!!.memo.passage)
    }

    /** 同じ本文でも引用を替えたら別のメモ。**確かめるではなく置く。** */
    @Test
    fun `引用を替えたら同じ本文でも確かめるではなく置く`() {
        val draft = MarginMemoDraft().quoted(quoteA, sectionB).edited("添える", sectionB)
        val submission = submissionOf(draft.text, sectionB.title, 1_000L, draft.passage)!!
        val sent = draft.submitted(submission)

        assertEquals(MemoSendAction.Verify, sent.sendAction())
        assertEquals(MemoSendAction.Place, sent.quoted(quoteB, sectionB).sendAction())
    }

    /**
     * **受理されたことと、今の書きかけを空にできたことは別**（→ features/margin_quote.md §5.6）。
     * 保存を待つ間に本文か引用を書き直していれば、受理されても書きかけを残し、空にしたとも言わない。
     */
    @Test
    fun `受理で書きかけを空にできるのは送った後に何も変えていないときだけ`() {
        val draft = MarginMemoDraft().quoted(quoteA, sectionB).edited("添える", sectionB)
        val submission = submissionOf(draft.text, sectionB.title, 1_000L, draft.passage)!!
        val sent = draft.submitted(submission)
        val textChanged = sent.edited("添える言葉を書き直した", sectionB)
        val quoteChanged = sent.quoted(quoteB, sectionB)

        assertTrue(sent.consumedBy(submission))
        assertEquals(MarginMemoDraft(), sent.accepted(submission))
        assertFalse(textChanged.consumedBy(submission))
        assertEquals("添える言葉を書き直した", textChanged.accepted(submission).text)
        assertFalse(quoteChanged.consumedBy(submission))
        assertEquals(quoteB, quoteChanged.accepted(submission).quote)
        assertNull(quoteChanged.accepted(submission).pending)
    }

    @Test
    fun `追わなくなった古い送信の受理では何も変えず、空にしたとも言わない`() {
        val first = MarginMemoDraft().edited("1件目", sectionB)
        val old = submissionOf(first.text, sectionB.title, 1_000L)!!
        val second = first.submitted(old).edited("2件目", sectionB)
        val newer = submissionOf(second.text, sectionB.title, 2_000L)!!
        val tracking = second.submitted(newer)

        assertFalse(tracking.consumedBy(old))
        assertEquals(tracking, tracking.accepted(old))
    }

    // ── 引用元の節だけを替えたとき（→ features/margin_quote.md §5.3）──────────────────

    /** 本番の取り方で [block] から「同じ一節」を引き、引用とその節を返す。 */
    private fun sameQuoteFrom(body: String, block: Int): Pair<DraftQuote, SectionRef> {
        val passages = buildPassageIndex(buildNoteSectionModel(body))
        val source = resolveQuoteAt("同じ一節", block, passages) as QuoteSource.Resolved
        return DraftQuote(MemoPassage.Quoted("同じ一節", source.context, truncated = false)) to passages.sections[block]
    }

    /**
     * **引用の値が同じでも、引用元の節だけを替えたら別の書きかけ。** 前の送信の受理で空にせず、確かめるではなく置く。
     * 節Aと節Bに「同じ一節」だけの段落があると、どちらから引いても引用は同じ値になる。
     */
    @Test
    fun `保存を待つ間に引用元の節だけを替えたら前の送信の受理で空にしない`() {
        listOf(
            "## A\n\n同じ一節\n\n## B\n\n同じ一節",
            // 同名の見出しの別の順番でも同じ。
            "## 節\n\n同じ一節\n\n## 節\n\n同じ一節"
        ).forEach { body ->
            val (quoteA, sectionA) = sameQuoteFrom(body, block = 1)
            val (quoteB, sectionB) = sameQuoteFrom(body, block = 3)
            assertEquals("引用の値が違う（この再現の前提が崩れた）", quoteA, quoteB)
            val draft = MarginMemoDraft().quoted(quoteA, sectionA).edited("添える言葉", sectionA)
            val sent = submissionOf(draft.text, draft.target?.title, 1_000L, draft.passage, target = draft.target)!!

            val requoted = draft.submitted(sent).quoted(quoteB, sectionB)
            val after = requoted.accepted(sent)

            assertEquals(MemoSendAction.Place, requoted.sendAction())
            assertFalse(requoted.consumedBy(sent))
            assertEquals("添える言葉", after.text)
            assertEquals(sectionB, after.target)
            assertEquals(quoteB, after.quote)
            assertNull(after.pending)
        }
    }

    @Test
    fun `何も替えなければ受理で空になる`() {
        val (quoteA, sectionA) = sameQuoteFrom("## A\n\n同じ一節\n\n## B\n\n同じ一節", block = 1)
        val draft = MarginMemoDraft().quoted(quoteA, sectionA).edited("添える言葉", sectionA)
        val sent = submissionOf(draft.text, draft.target?.title, 1_000L, draft.passage, target = draft.target)!!
        val waiting = draft.submitted(sent)

        assertEquals(MemoSendAction.Verify, waiting.sendAction())
        assertTrue(waiting.consumedBy(sent))
        assertEquals(MarginMemoDraft(), waiting.accepted(sent))
    }

    /** 書き込み先が決まる前に置いた送信（置いた時点の本文の節へ書いた）は、そのまま待てば同じ送信として確かめ、受理で空にする。 */
    @Test
    fun `書き込み先が決まる前に置いた送信も受理で空になる`() {
        val draft = MarginMemoDraft().edited("節が分かる前に書いた", bodySection = null)
        val sent = submissionOf(draft.text, sectionC.title, 1_000L, target = sectionC)!!
        val waiting = draft.submitted(sent)

        assertEquals(MemoSendAction.Verify, waiting.sendAction())
        assertEquals(MarginMemoDraft(), waiting.accepted(sent))
    }

    // ── 引用を外したときの控え（→ features/margin_quote.md §5.3）────────────────────

    @Test
    fun `引用で書き込み先を別の節へ移すと書き始めた頃の控えを捨て、外しても取り直さない`() {
        val draft = MarginMemoDraft()
            .edited("書いていた", bodySection = sectionB, seenAtStart = seenTop)
            .quoted(quoteA, sectionC)
            .unquoted()

        assertNull(draft.seen)
        assertNull(draft.passage)
        assertEquals(sectionC, draft.target)
        assertEquals("書いていた", draft.text)
    }

    @Test
    fun `引用元が同じ節なら書き始めた頃の控えを保つ`() {
        val draft = MarginMemoDraft()
            .edited("書いていた", bodySection = sectionB, seenAtStart = seenTop)
            .quoted(quoteA, sectionB)
            .unquoted()

        assertEquals(seenTop, draft.passage)
    }
}
