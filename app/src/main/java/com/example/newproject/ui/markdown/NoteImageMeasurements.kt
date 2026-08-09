package com.example.newproject.ui.markdown

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import com.example.newproject.domain.markdown.MarkdownBlock

/** ノート内の画像ブロックの位置と参照先。**判定を純関数へ渡すための最小の材料。** */
internal data class NoteImageBlockRef(val blockIndex: Int, val reference: String)

/** 本文から画像ブロックだけを拾う。**モデルが変わらない限り作り直さない**（毎フレーム走査しない）。 */
internal fun imageBlockRefs(blocks: List<MarkdownBlock>): List<NoteImageBlockRef> =
    blocks.mapIndexedNotNull { index, block ->
        (block as? MarkdownBlock.Image)?.let { NoteImageBlockRef(index, it.target) }
    }

/**
 * まだ寸法が取れていない画像のうち、最も手前の位置。無ければ null。
 *
 * **「いま描かれている画像」ではなく、ノート内の全画像から判定する。**
 * 画面外へスクロールした画像は Composable ごと破棄され、測定も途中でキャンセルされる。
 * 破棄を「確定した」と読むと、**測っていないのに未確定でなくなる**ため、
 * その先の報告が通ってしまう（実機で実際にそうなった）。
 *
 * 判定材料を**本文の構造と測定キャッシュだけ**にすれば、
 * コンポジションの寿命から独立する。
 */
internal fun firstUnmeasuredImageIndex(
    imageBlocks: List<NoteImageBlockRef>,
    measuredReferences: Set<String>
): Int? = imageBlocks.firstOrNull { it.reference !in measuredReferences }?.blockIndex

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
 * そこで測定済みの参照を公開し、`ReadingProgressReporter` が
 * [firstUnmeasuredImageIndex] で**未測定より後ろの報告を止める**ために読む。
 *
 * **状態は「測れたかどうか」だけを持つ。** 「いま未確定か」を別に持つと、
 * 画面外への破棄で解除されて判定が崩れる。
 *
 * ## 「測れた」の定義
 *
 * **測定に失敗した場合も測れたとみなす。** 失敗パネルは画面1枚ぶんの高さで固定され、
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

    /** 既に測ってあれば返す。**全画面で測り直さないための共有。** */
    fun measurementOf(block: MarkdownBlock.Image): NoteImageMeasurement? = byReference[block.target]

    fun record(block: MarkdownBlock.Image, measurement: NoteImageMeasurement) {
        byReference[block.target] = measurement
    }

    /** 測定済みの参照。**読むと購読になる**ので、記録されれば報告側の判定が動く。 */
    fun measuredReferences(): Set<String> = byReference.keys.toSet()
}
