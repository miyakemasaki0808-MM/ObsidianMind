package com.example.newproject.domain

import com.example.newproject.model.MarginMemo
import com.example.newproject.model.MemoFileRead
import com.example.newproject.model.SectionRef
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
internal fun submissionOf(raw: String, sectionTitle: String?, nowEpochMillis: Long): MemoSubmission? {
    val composed = composeMarginMemo(raw)
    if (composed.isBlank) return null
    return MemoSubmission(
        raw = raw,
        memo = MarginMemo(
            text = composed.text,
            writtenAtEpochMillis = nowEpochMillis,
            sectionTitle = composeMemoSectionTitle(sectionTitle)
        ),
        wasTruncated = composed.wasTruncated
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
 *
 * 本文の節がまだ分からない（解析の前）ときは決めずに置き、次の入力で決める。
 * **未確定の送信には触らない** — 末尾の空白を足しただけの編集でも、整えた本文は同じになるため。
 */
internal fun MarginMemoDraft.edited(text: String, bodySection: SectionRef?): MarginMemoDraft = copy(
    text = text,
    target = when {
        text.isEmpty() -> null
        target != null -> target
        else -> bodySection
    }
)

/**
 * 保存を頼んだ。**入力は空にしない** — 置けなかったときに原文を残すため。
 * 前に未確定の送信があっても、保存値の違う内容を新しく送ったので追わなくなる。
 */
internal fun MarginMemoDraft.submitted(submission: MemoSubmission): MarginMemoDraft = copy(pending = submission)

/**
 * 受理された。送信を消費し、**入力が原文のままのときだけ**空にして書き込み先を放す。
 * 待っている間に書き直した文字は残す。**追わなくなった古い送信の結果では何も消さない。**
 */
internal fun MarginMemoDraft.accepted(submission: MemoSubmission): MarginMemoDraft = when {
    pending != submission -> this
    text == submission.raw -> copy(text = "", target = null, pending = null)
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
        composed.isBlank -> MemoSendAction.None
        pending != null && composed.text == pending.memo.text -> MemoSendAction.Verify
        else -> MemoSendAction.Place
    }
}
