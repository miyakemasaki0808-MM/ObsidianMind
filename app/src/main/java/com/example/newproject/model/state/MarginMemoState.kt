package com.example.newproject.model.state

import com.example.newproject.model.MarginMemo

/**
 * 余白メモのシートの状態。
 *
 * **端末AIの状態を持たない。** この機能はAIを1回も呼ばないので、
 * `AiNotice` に相当する枝が要らない（Reflect 系で唯一）。
 */
sealed class MarginMemoState {
    data object Idle : MarginMemoState()

    /** サイドカーを1件読んでいる。**シートを開いたときだけ**通る。 */
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
         * **受け取れたメモの通算件数。** 画面が入力欄を空にしてよい合図として使う。
         *
         * 入力欄を**状態から導かない**ためにこれがある。`status` は保存後も残るので、
         * 「置けたら空にする」を条件式で書くと**再コンポーズのたびに成立し、
         * 2件目に書いた文字が入力するそばから消える。**
         *
         * **置けなかった回は増えない。** だから画面は入力を消さずに済み、
         * 上限超過で切り詰める前の**原文がそのまま手元に残る。**
         */
        val acceptedCount: Long = 0
    ) : MarginMemoState()

    /** 読み込みに失敗した。書くことはできるので、入口は閉じない。 */
    data class Error(val message: String) : MarginMemoState()
}

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
    Failed
}
