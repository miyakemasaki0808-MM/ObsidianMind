package com.example.newproject.model.state

import com.example.newproject.model.Reflection

/**
 * ノートへのひとこと（旧「AI補記メモ」）の状態。
 *
 * **`isViewed` を持たない。** 旧補記は結果が専用画面にあったので「見たかどうか」を
 * 管理してAIタブへバッジを出していたが、ひとことは読書画面へ直接1文が出るため
 * 未確認という概念そのものが無い（→ design/reflect_remark.md §7.1）。
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
        /** 返事の保存中。ボタンの二度押しを止めるためだけに持つ。 */
        val isSavingReply: Boolean = false,
        /**
         * 返事がどこにも保存できなかった。
         *
         * **「預かった」とは区別する。** 預かった場合は離脱時に書かれるので
         * ユーザーへ失敗を見せる必要がないが、こちらは本当に消えるため、
         * 画面へ出して書き直せる状態を保つ。
         */
        val isReplyUnsaved: Boolean = false
    ) : RemarkState()

    /** モデルが「出すものが無い」と表明した。正常な結果。 */
    data class Empty(val sourceTitle: String) : RemarkState()

    /** 応答は来たが検査を通らなかった（短すぎる・長すぎる・一般論・候補外リンク）。 */
    data class Unusable(val sourceTitle: String) : RemarkState()

    /** 生成そのものが失敗した（非対応端末・DL失敗・例外）。 */
    data class Error(val message: String, val sourceTitle: String? = null) : RemarkState()
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
}
