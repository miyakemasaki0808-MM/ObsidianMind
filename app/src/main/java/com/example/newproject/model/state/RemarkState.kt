package com.example.newproject.model.state

import com.example.newproject.model.Reflection

/**
 * ノートへのひとこと（旧「AI補記メモ」）の状態。
 *
 * **`isViewed` を持たない。** 旧補記は結果が専用画面にあったので「見たかどうか」を
 * 管理してAIタブへバッジを出していたが、ひとことは読書画面へ直接1文が出るため
 * 未確認という概念そのものが無い（→ features/reflect_remark.md §7.1）。
 *
 * **[Empty] は失敗ではない。** 「出すものが無い」は正常な結果で、
 * ユーザーには固定文で伝える（AIに「補記不要です」と言わせない → §5）。
 *
 * **[Empty] と [Unusable] を畳まない。** どちらも「ひとことが出ない」だが、
 * **ユーザーの次の行動が違う** — 前者は再試行しても同じ、後者は再試行が効く。
 * 畳むと、モデルが書式を守れなかっただけの回が
 * 「問いが見つかりませんでした」に化けて、再試行の余地が伝わらない。
 */
sealed class RemarkState {
    object Idle : RemarkState()
    data class Loading(val sourceTitle: String) : RemarkState()
    /**
     * ひとことが届いた（返事があれば一緒に持つ）。
     *
     * **文字列2本ではなく [Reflection] の1組で持つ。** 片方だけが残る状態
     * （返事だけあって元の問いが分からない／問いを作り直したのに古い返事が残る）を
     * 型として作れなくするため。
     */
    data class Ready(
        val sourceTitle: String,
        val reflection: Reflection,
        /** 返事の保存の進み具合。**Boolean 2本に分けない**（→ [ReplyStatus]）。 */
        val replyStatus: ReplyStatus = ReplyStatus.None
    ) : RemarkState()

    /** モデルが「出すものが無い」と表明した。正常な結果。 */
    data class Empty(val sourceTitle: String) : RemarkState()

    /** 応答は来たが検査を通らなかった（短すぎる・長すぎる・一般論・候補外リンク）。 */
    data class Unusable(val sourceTitle: String) : RemarkState()

    /** 生成そのものが失敗した（DL失敗・例外）。 */
    data class Error(val message: String, val sourceTitle: String? = null) : RemarkState()

    /**
     * 端末AIの状態を説明している。
     *
     * **[Error] へ畳まない。** 畳んでいたころは非対応端末で「ひとことをもらえませんでした。
     * ひとことはこの端末では利用できません。」と出たうえ、**再試行ボタンが押せたまま**だった。
     * [Empty] と [Unusable] を分けたのと同じ理屈で、次の行動が違うものを1つにしない。
     */
    data class AiNotice(val notice: AiStatusNotice, val sourceTitle: String) : RemarkState()
}

/**
 * 「ひとことをもらう」を押させてよいか。
 *
 * 生成中の二重起動を防ぐほか、**恒久非対応の端末では押しても同じ答えしか返らない**ので無効にする。
 *
 * **通知のCTA（[AiNoticeAction]）で判定しない。** DL実行中の説明はCTAを持たないが、
 * 入口まで閉じると**DL完了後に押し直せなくなる**。閉じてよいのは
 * [AiStatusNotice.canTryAgainLater] が false のときだけ。
 */
internal fun RemarkState.canRequestRemark(): Boolean = when (this) {
    is RemarkState.Loading -> false
    is RemarkState.AiNotice -> notice.canTryAgainLater
    else -> true
}

/**
 * 返事の保存がどこまで進んだか。
 *
 * **Boolean 2本（保存中／未保存）に分けない。** 分けると
 * 「保存中かつ未保存」のような意味の無い組み合わせが型として作れてしまい、
 * 実際に「預かっただけ」と「保存済み」を同じ顔で出す誤りをやった。
 *
 * [Held] を [Saved] と呼ばないのが要点 —
 * **預かった時点ではまだファイルに書かれていない。**
 * 離脱時の書き込みで確定するので、それまでは「保存中」と正直に出す。
 */
enum class ReplyStatus {
    /** まだ保存操作をしていない。 */
    None,
    /** 書き込み中。 */
    Saving,
    /** 預かった。離脱時に確定する（＝まだ消えうる）。 */
    Held,
    /** サイドカーへ書けた。 */
    Saved,
    /** どこにも残っていない。書き直せる状態を保つ。 */
    Failed
}

/**
 * Snackbar を同じ状態で二度出さないための識別子。
 *
 * `isViewed` を持たないので、旧補記のように「未確認のあいだ出し続ける」形にはならない。
 * 状態が変わったときに1回だけ通知し、確認したかどうかは追わない。
 */
internal fun RemarkState.toEventKey(): String? = when (this) {
    is RemarkState.Idle -> null
    is RemarkState.Loading -> "loading:$sourceTitle"
    // 返事を残した瞬間にも通知したいので、返事まで含めて識別する。
    // **返事の保存では通知を出し直さない。** ここに reply や isSavingReply を含めると、
    // 返事を書いた画面の上で「ひとことが届きました」が再度出る。
    // 通知したいのは「ひとことが届いた」瞬間だけなので、ひとことだけで識別する。
    is RemarkState.Ready -> "ready:$sourceTitle:${reflection.remark.hashCode()}"
    is RemarkState.Empty -> "empty:$sourceTitle"
    is RemarkState.Unusable -> "unusable:$sourceTitle"
    is RemarkState.Error -> "error:$sourceTitle:$message"
    is RemarkState.AiNotice -> "notice:$sourceTitle:${notice.message}"
}
