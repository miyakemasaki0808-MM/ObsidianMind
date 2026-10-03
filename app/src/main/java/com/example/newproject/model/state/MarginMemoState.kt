package com.example.newproject.model.state

import com.example.newproject.model.MarginMemo

/**
 * 余白メモのシートと余白ペインの状態（2つの面で同じものを見せる）。
 *
 * **端末AIの状態を持たない。** この機能はAIを1回も呼ばないので、
 * `AiNotice` に相当する枝が要らない（Reflect 系で唯一）。
 */
sealed class MarginMemoState {
    data object Idle : MarginMemoState()

    /** サイドカーを1件読んでいる。**ノートを表示した後**（と、読めなかった後に面を出し直したとき）だけ通る。 */
    data object Loading : MarginMemoState()

    /**
     * このノートのメモ。**新しい順**で持つ（保存の並びは古い順なので、ここで反転済み）。
     */
    data class Ready(
        val memos: List<MarginMemo>,
        /** 直前の保存がどこまで進んだか。**Boolean の束に分けない**（→ [MemoSaveStatus]）。 */
        val status: MemoSaveStatus = MemoSaveStatus.None,
        /**
         * 直前の保存で上限を超えて切り詰めたか。
         *
         * **切ったら必ず示す**ための欄で、[status] とは独立に立つ
         * （切り詰めたうえで保存は成功する）。
         */
        val wasTruncated: Boolean = false,
        /**
         * 前回の読書（→ features/margin_pane.md §5.7）。今の読書を始める前の最新の訪問で、無ければ null。
         * **今の読書の訪問は数えない**ので、読み直しても、この読書の間に自分の訪問へ置き換わらない。
         */
        val previousVisit: PreviousVisit? = null
    ) : MarginMemoState()

    /** 読み込みに失敗した。書くことはできるので、入口は閉じない。 */
    data class Error(val message: String) : MarginMemoState()
}

/**
 * 前回の読書。**どの節に出すかは、画面が今の見出しと照合して決める**（見出し名は保存値のまま持つ）。
 */
data class PreviousVisit(
    /** いちばん深く読んだ節の見出し名。見出しより前で離れた回と、見出しの無いノートは null。 */
    val sectionTitle: String?,
    val atEpochMillis: Long,
    /** 最後まで読んだ回か。そうなら出さない — その前の読みかけまでは遡らない。 */
    val readToEnd: Boolean
)

/**
 * メモの保存がどこまで進んだか。
 *
 * **Boolean の束に分けない。** 分けると「保存中かつ未保存」のような
 * 意味の無い組み合わせが型として作れてしまう。
 *
 * [Held] を [Saved] と呼ばないのが要点 — **預かった時点ではまだファイルに書かれていない。**
 * 離脱時の書き込みで確定するので、それまでは正直に「保存中」と出す。
 *
 * **[Full] を [Failed] へ畳まない。** 再試行で直るものと、
 * 1件消さなければ直らないものは、ユーザーの次の行動が違う。
 */
enum class MemoSaveStatus {
    /** まだ保存操作をしていない。 */
    None,
    /** 書き込み中。 */
    Saving,
    /** 預かった。離脱時に確定する（＝まだ消えうる）。 */
    Held,
    /** サイドカーへ書けた。 */
    Saved,
    /** 上限に達して置けない。**入力欄の文字は消さない。** */
    Full,
    /** どこにも残っていない。書き直せる状態を保つ。 */
    Failed,
    /**
     * 離れている間に頼んだ送信を、戻って読んでも確かめられなかった（痕跡を読めない）。
     * **未保存と断定しない** — 送信と原文を持ち続け、ボタンは元の送信を確かめる役目になる。
     */
    Unconfirmed
}
