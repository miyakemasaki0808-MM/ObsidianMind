package com.example.newproject.ui.markdown

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import com.example.newproject.domain.markdown.MarkdownBlock

/**
 * ノート内画像の寸法を**通常表示と全画面で共有する**入れ物。
 *
 * ## なぜ hoist するのか
 *
 * 寸法は元々 `MarkdownImage` の `remember(block)` に閉じていた。全画面は新しい
 * コンポジションなので、**入った瞬間に未計測へ戻る。** ところが全画面は
 * `rememberLazyListState(tabListState.firstVisibleItemIndex, ...)` で
 * **スクロール位置は引き継ぐ**ので、引き継いだオフセットが仮の高さを超え、
 * **まだ読んでいない後続ブロックが可視項目になる。**
 *
 * 最深到達点は後から下がらないため、この誤りは**サイドカーへ永続化される。**
 * ここで測定結果を持ち回れば、全画面へ入っても測り直しが起きない。
 *
 * ## もう1つの役目: 報告の抑止
 *
 * 共有だけでは**初回の測定を待っている間にスクロールした場合**が残る。
 * そこで「どのブロックがまだ確定していないか」も持ち、
 * `ReadingProgressReporter` が**未確定より後ろの報告を止める**ために読む
 * （→ `shouldReportReadingProgress`）。
 *
 * ## 「確定」の定義
 *
 * **測定に失敗した場合も確定とみなす。** 失敗パネルは画面1枚ぶんの高さで固定され、
 * 後から縮まない。**高さが動かないなら、そこから先の可視判定は正しい。**
 * 止めたいのは「これから高さが変わるもの」だけである。
 *
 * ## 寿命
 *
 * ノート単位。`sectionModel` を鍵に作り直すことで、ノートが変われば捨てられる。
 * 画像の同一性は**参照文字列**（`target`）で見る — 同じ画像を2箇所から参照していれば
 * 測定を1回で済ませられるうえ、ブロックの入れ替えにも耐える。
 */
@Stable
internal class NoteImageMeasurements {

    private val byReference = mutableStateMapOf<String, NoteImageMeasurement>()
    private val unsettledBlockIndices = mutableStateMapOf<Int, Unit>()

    /** 既に測ってあれば返す。**全画面で測り直さないための共有。** */
    fun measurementOf(block: MarkdownBlock.Image): NoteImageMeasurement? = byReference[block.target]

    fun record(block: MarkdownBlock.Image, measurement: NoteImageMeasurement) {
        byReference[block.target] = measurement
    }

    /** そのブロックの高さがまだ動きうるか。**確定したら必ず解除する。** */
    fun setUnsettled(blockIndex: Int, unsettled: Boolean) {
        if (unsettled) unsettledBlockIndices[blockIndex] = Unit else unsettledBlockIndices.remove(blockIndex)
    }

    /**
     * 寸法未確定の画像のうち、最も手前の index。無ければ null。
     *
     * **最小値を見るのは、手前に1つでも未確定があればその先の可視判定が信用できない**ため。
     */
    fun firstUnsettledBlockIndex(): Int? = unsettledBlockIndices.keys.minOrNull()
}
