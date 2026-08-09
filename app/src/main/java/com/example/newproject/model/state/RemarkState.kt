package com.example.newproject.model.state

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
    data class Ready(val sourceTitle: String, val remark: String) : RemarkState()

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
    is RemarkState.Ready -> "ready:$sourceTitle:${remark.hashCode()}"
    is RemarkState.Empty -> "empty:$sourceTitle"
    is RemarkState.Unusable -> "unusable:$sourceTitle"
    is RemarkState.Error -> "error:$sourceTitle:$message"
}
