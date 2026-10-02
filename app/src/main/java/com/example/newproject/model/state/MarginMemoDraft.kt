package com.example.newproject.model.state

import com.example.newproject.model.MarginMemo
import com.example.newproject.model.SectionRef

/**
 * 1ノート分の余白メモの書きかけ（→ features/margin_pane.md §6.1）。
 *
 * **文字・書き込み先・未確定の送信を一組で持つ。** 文字だけを持ち上げると、書き込み先と受理の対応が切れる。
 * どのノートの書きかけかは、持ち主の一覧の鍵が持つ。
 */
data class MarginMemoDraft(
    val text: String = "",
    /** 書き込み先。**書き始めた時点の本文の節で、文字がある間は動かない。** null はまだ書き始めていない。 */
    val target: SectionRef? = null,
    /** 受理が確かめられていない送信。**1ノート1件まで**で、入力欄とは別に持ち続ける。 */
    val pending: MemoSubmission? = null
) {
    /** 何も持っていない。一覧から外してよい。 */
    val isEmpty: Boolean get() = text.isEmpty() && pending == null
}

/**
 * 保存を頼んだ1回の送信（→ features/margin_pane.md §6.2）。
 *
 * [memo] は整えた後の保存値と送信の時刻で、**照合はこの2つの組で行う。**
 * 整えると原文と一致しなくなるので、入力欄を空にしてよいかは [raw] で決める。
 */
data class MemoSubmission(
    /** 送信時の原文。今の入力がこれと同じときだけ、受理で入力欄を空にする。 */
    val raw: String,
    val memo: MarginMemo,
    /** 1024バイトで切ったか。**受理の後も失わない**（戻った後の照合で受理が分かったときも知らせる）。 */
    val wasTruncated: Boolean
)
