package com.example.newproject.domain

import com.example.newproject.model.MarginMemo
import com.example.newproject.model.MemoFileRead
import com.example.newproject.model.MemoPassage
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.DraftQuote
import com.example.newproject.model.state.MarginMemoDraft
import com.example.newproject.model.state.MemoSubmission

// ---------------------------------------------------------------------------
// 余白メモの書きかけと送信の規則（→ features/margin_pane.md §6.1・§6.2）。
//
// 目的は1つ。**1回の送信で1件だけ保存され、未保存と断定しない。**
// 受理は件数ではなく、送信の保存値と時刻の組で照合する（件数はノートを替えると0に戻る）。
// ---------------------------------------------------------------------------

/** 送信が受理されたか。 */
internal enum class SubmissionCheck {
    /** 永続・保存待ち・預かりのどこかに組がある。 */
    Accepted,

    /** 無いことが確かめられた。送信を取り下げ、原文を残す。 */
    NotAccepted,

    /** 永続を読めず、保存待ち・預かりにも無い。**送信と原文を持ち続ける。** */
    Unconfirmed
}

/** ボタンの役目。**今の入力を整えた本文で決める。** */
internal enum class MemoSendAction {
    /** 新しいメモとして送る。 */
    Place,

    /** 未確定の送信と同じ内容。**新しい保存は頼まず**、元の送信を確かめる。 */
    Verify,

    /** 置くものが無い。 */
    None
}

/**
 * 送信を作る。空白だけなら null。
 *
 * **整えるのはここの1回だけ**で、その結果を保存にも照合にも使う。整え直すと、
 * 切り詰めや制御文字の扱いで照合の鍵がずれうる。時刻もここで1度だけ決める。
 */
internal fun submissionOf(
    raw: String,
    sectionTitle: String?,
    nowEpochMillis: Long,
    passage: MemoPassage? = null,
    target: SectionRef? = null
): MemoSubmission? {
    val composed = composeMarginMemo(raw)
    if (composed.isBlank) return null
    return MemoSubmission(
        raw = raw,
        memo = MarginMemo(
            text = composed.text,
            writtenAtEpochMillis = nowEpochMillis,
            sectionTitle = composeMemoSectionTitle(sectionTitle),
            passage = passage
        ),
        wasTruncated = composed.wasTruncated,
        target = target
    )
}

/**
 * 送信が受理されたかを、読み込んだ一覧で決める。
 *
 * **鍵は保存値と時刻の組。** メモの合流で重複を畳む鍵と同じなので、本文がたまたま同じ別のメモと取り違えない。
 * 一覧に無いことを未受理と読んでよいのは、ファイルを読めたか無いと確かめたときだけ（→ lessons L47）。
 */
internal fun checkSubmission(
    submission: MemoSubmission,
    memos: List<MarginMemo>,
    fileRead: MemoFileRead
): SubmissionCheck = when {
    memos.any { it.text == submission.memo.text && it.writtenAtEpochMillis == submission.memo.writtenAtEpochMillis } ->
        SubmissionCheck.Accepted
    fileRead == MemoFileRead.Unconfirmed -> SubmissionCheck.Unconfirmed
    else -> SubmissionCheck.NotAccepted
}

/**
 * 入力を変えた。**書き始めた瞬間の本文の節を書き込み先にし、文字がある間は動かさない。** 空にしたら放す。
 * 書き込み先を決める瞬間に、そのとき読んでいた位置 [seenAtStart] も控える（引用が付いていれば控えない → features/margin_quote.md §5.4）。
 *
 * 本文の節がまだ分からない（解析の前）ときは決めずに置き、次の入力で決める。
 * **引用が付いていれば、空にしても書き込み先を放さない** — 書き込み先は引用元の節で、引用は残っている。
 * **未確定の送信には触らない** — 末尾の空白を足しただけの編集でも、整えた本文は同じになるため。
 */
internal fun MarginMemoDraft.edited(text: String, bodySection: SectionRef?, seenAtStart: MemoPassage.Seen? = null): MarginMemoDraft =
    when {
        text.isEmpty() -> copy(text = text, target = if (quote != null) target else null, seen = null)
        target != null -> copy(text = text)
        else -> copy(text = text, target = bodySection, seen = if (bodySection != null && quote == null) seenAtStart else null)
    }

/**
 * 引用を付けた。**書いている途中でも、書き込み先を引用元の節 [section] へ移す** — 引用は利用者が場所を選ぶ明示の操作で、
 * 本文のスクロールとは違う（→ features/margin_quote.md §5.2）。引用元が決まらなければ呼ぶ側が今の本文の節を渡す。
 * 付いていた引用とは入れ替え、書いた文字と未確定の送信はそのまま。
 *
 * **書き込み先を別の節へ移したら、書き始めた頃の位置の控えを捨てる。** 控えは前の書き込み先の節の段落を指すので、
 * 残すと引用を外して置いたメモが、利用者が選んだ書き込み先ではなく前の節へ並ぶ（→ features/margin_quote.md §5.3）。
 * 外したときに今の位置を取り直すこともしない — それは書き始めた頃ではない。
 */
internal fun MarginMemoDraft.quoted(quote: DraftQuote, section: SectionRef?): MarginMemoDraft {
    val nextTarget = section ?: target
    return copy(quote = quote, target = nextTarget, seen = if (nextTarget == target) seen else null)
}

/** 引用を外した（✕）。**引用だけを消す。** 文字があれば書き込み先はそのまま、空なら放す。 */
internal fun MarginMemoDraft.unquoted(): MarginMemoDraft =
    copy(quote = null, target = if (text.isEmpty()) null else target)

/**
 * 保存を頼んだ。**入力は空にしない** — 置けなかったときに原文を残すため。
 * 前に未確定の送信があっても、保存値の違う内容を新しく送ったので追わなくなる。
 */
internal fun MarginMemoDraft.submitted(submission: MemoSubmission): MarginMemoDraft = copy(pending = submission)

/**
 * この送信の受理で、今の書きかけを空にできるか。**入力が原文のままで、引用も送ったものと同じ**ときだけ。
 *
 * 受理されたことと、今の書きかけを消費できたことは別である。並べ読みの右を資料へ戻すのは後者のときだけ
 * （保存を待つ間に書き直していれば、まだ書き終えていない → features/margin_quote.md §5.6）。
 */
internal fun MarginMemoDraft.consumedBy(submission: MemoSubmission): Boolean =
    pending == submission && text == submission.raw && passage == submission.memo.passage && sameTargetAs(submission)

/**
 * 書き込み先が送ったときと同じか。**引用の文字が同じでも、引用元の節だけを替えれば別の書きかけ**として扱う。
 * どちらかが分からないとき（書き込み先が決まる前に置いた送信）は同じとみなす。違うと言えるのは両方が分かるときだけ。
 */
private fun MarginMemoDraft.sameTargetAs(submission: MemoSubmission): Boolean =
    target == null || submission.target == null || target == submission.target

/**
 * 受理された。送信を消費し、**[consumedBy] のときだけ**空にして書き込み先・引用・控えを放す。
 * 待っている間に書き直した文字や替えた引用・引用元の節は残す。**追わなくなった古い送信の結果では何も消さない。**
 */
internal fun MarginMemoDraft.accepted(submission: MemoSubmission): MarginMemoDraft = when {
    pending != submission -> this
    consumedBy(submission) -> MarginMemoDraft()
    else -> copy(pending = null)
}

/** 受理されなかった。送信を取り下げ、原文はそのまま残す。 */
internal fun MarginMemoDraft.rejected(submission: MemoSubmission): MarginMemoDraft =
    if (pending != submission) this else copy(pending = null)

/** 照合の結果を書きかけへ当てる。 */
internal fun MarginMemoDraft.settled(submission: MemoSubmission, check: SubmissionCheck): MarginMemoDraft =
    when (check) {
        SubmissionCheck.Accepted -> accepted(submission)
        SubmissionCheck.NotAccepted -> rejected(submission)
        SubmissionCheck.Unconfirmed -> this
    }

/**
 * ボタンの役目。未確定の送信と**整えた本文が同じなら「確かめる」**で、新しい保存は頼まない。
 *
 * 空白や末尾の改行を足しただけ、1024バイトより後ろだけを直した、編集して元へ戻した、のどれも
 * 整えた本文は同じになる。原文で比べると、同じ本文を別の時刻でもう1件保存してしまう。
 */
internal fun MarginMemoDraft.sendAction(): MemoSendAction {
    val composed = composeMarginMemo(text)
    return when {
        // **引用だけでは置かない。** 自分の言葉が要る（→ features/margin_quote.md 判断2）。
        composed.isBlank -> MemoSendAction.None
        // 引用を替えたら同じ本文でも別のメモなので、確かめるではなく置く。
        pending != null && composed.text == pending.memo.text && passage == pending.memo.passage && sameTargetAs(pending) ->
            MemoSendAction.Verify
        else -> MemoSendAction.Place
    }
}
