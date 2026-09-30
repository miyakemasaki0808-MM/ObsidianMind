package com.example.newproject.domain

/**
 * 余白メモの書きかけ。**文字・送った原文・最後に見た受理件数・文字が属するノートを一組で持つ。**
 *
 * 文字だけを持ち上げると、受理の対応が表示部品の寿命に残る。表示部品を外している間に保存が
 * 受理されると、作り直した部品は何を送ったかを知らず、受理済みの文字が入力欄に残って再送できてしまう。
 *
 * **ノートの照合は値の中で行う。** 画面の保存値から戻すときは「前に見たノート」が手元に無いので、
 * 外から渡す比較の鍵では、非表示の間に替わったノートを見分けられない（→ features/margin_pane.md §11）。
 */
internal data class MarginMemoInput(
    /** この文字を書いたノート。読み込み中は null。 */
    val noteKey: String?,
    val text: String = "",
    /** 保存を頼んだときの原文。受理を待っている間だけ持つ。 */
    val submitted: String? = null,
    /** 最後に見た受理の通算件数（→ `MarginMemoState.Ready.acceptedCount`）。 */
    val seenAcceptedCount: Long = 0L
) {
    /**
     * 今のノートと受理件数に合わせる。**別のノートの書きかけは捨てる** — 残すと、前のノートに
     * 書きかけた言葉を今のノートへ置けてしまう。
     *
     * 受理件数が増えていたら、**送った原文のままの入力だけ**を空にする。待っている間に書き直した文字は残す。
     * 件数は読み直しで0へ戻ることがあるので、減ったときは基準だけを合わせ、何も消さない。
     */
    fun reconciled(noteKey: String?, acceptedCount: Long): MarginMemoInput {
        val own = if (this.noteKey == noteKey) this else MarginMemoInput(noteKey, seenAcceptedCount = acceptedCount)
        return when {
            acceptedCount > own.seenAcceptedCount -> own.copy(
                text = if (own.text == own.submitted) "" else own.text,
                submitted = null,
                seenAcceptedCount = acceptedCount
            )
            acceptedCount < own.seenAcceptedCount -> own.copy(seenAcceptedCount = acceptedCount)
            else -> own
        }
    }

    fun edited(text: String): MarginMemoInput = copy(text = text)

    /** 保存を頼んだ。**入力は空にしない** — 置けなかったときに原文を残すため。 */
    fun submitting(text: String): MarginMemoInput = copy(submitted = text)
}
