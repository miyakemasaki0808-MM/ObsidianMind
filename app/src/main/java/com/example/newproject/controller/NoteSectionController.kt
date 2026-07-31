package com.example.newproject.controller

import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.domain.markdown.buildNoteSectionModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ノート本文の [NoteSectionModel]（＝Markdownのパース結果）を **Main の外で1回だけ**作り、
 * 通常表示と全画面表示へ配る。
 *
 * ## なぜ必要か
 *
 * 以前は `NoteReaderTab` と `FullscreenNoteScreen` がそれぞれの `remember` で
 * [buildNoteSectionModel] を同期実行していた。表示用本文は最大1MBまで許可されており、
 * 同じパーサを使うAI入力側の実測では **1MBで約460ms・200KBで約20ms** かかる。
 * つまり長文ノートを開く瞬間と全画面へ入る瞬間に、UIスレッドがそれぞれ止まっていた
 * （Composable が別なので結果も共有されず、同じ本文を2回解析していた）。
 *
 * AI入力の抜粋生成は同じ理由で既に `Dispatchers.Default` へ逃がしてある
 * （[com.example.newproject.domain.NoteExcerptBuilder]）。本クラスはその横展開で、
 * **表示経路に残っていた最後の1本**にあたる。
 *
 * ## なぜ状態が [com.example.newproject.model.NoteUiState] に入らないか
 *
 * 依存方向の規約で `model` パッケージはプロジェクト内の何もimportできない（葉である）。
 * [NoteSectionModel] は `domain.markdown` にあり、`surroundingContext()` のような
 * 振る舞いを持つ純データ型ではないため `model` へは移せない。したがって
 * `NoteUiState` には入れられず、テーマと同じく**独立した [StateFlow]** として配る。
 *
 * ## キャンセルについて
 *
 * [buildNoteSectionModel] は素の同期関数なので、[cancelAndClear] を呼んでも
 * **走行中の解析そのものは中断されない**（最後まで走り切ってから結果が捨てられる）。
 * 抜粋生成と同じ性質で、UIスレッドを塞がないことが目的である以上これで足りる。
 *
 * 結果を捨てているのは `parseJob` のキャンセルである。他のControllerが持つ
 * `activeRequestId` ＋ `isCurrent()` は**ここでは持たない** — 状態を書き換える
 * 経路が [parse] と [cancelAndClear] の2つしかなく、どちらも必ず先にJobを
 * キャンセルするため、世代照合に到達する経路が存在しない。実際に requestId ガードを
 * 書いてから1行消す変異確認を行い、**どのテストも落ちなかった**ので冗長と判断して
 * 削除した（同じ判断の前例が `architecture.md` のA案の節にある）。
 * **再び足す場合は、先に「ガードを消すと落ちるテスト」を書けることを確かめること。**
 */
class NoteSectionController(
    private val scope: CoroutineScope,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val mutableModel = MutableStateFlow<NoteSectionModel?>(null)

    /** パース完了前と、ノートを離れている間は null。 */
    val model: StateFlow<NoteSectionModel?> = mutableModel.asStateFlow()

    private var parseJob: Job? = null

    /**
     * 本文の解析を始める。**ここで現在値を null に戻さない**のが要点で、
     * 蒸留の保存後に本文を差し替えるとき（[NoteSessionCoordinator.applyReloadedBody]）に
     * 本文が数百ミリ秒消えるのを避ける。ノート切替では [cancelAndClear] が先に
     * 呼ばれるので、旧ノートのブロックが新しいノートの画面へ残ることはない。
     */
    fun parse(content: String) {
        // 走行中の解析を先に捨てる。これが「後着した旧本文で上書きしない」唯一の仕組み。
        parseJob?.cancel()
        parseJob = scope.launch {
            val parsed = withContext(parseDispatcher) { buildNoteSectionModel(content) }
            mutableModel.value = parsed
        }
    }

    /** ノート・Vault切替時に解析を止め、旧ノートのブロックが残らないようにする。 */
    fun cancelAndClear() {
        parseJob?.cancel()
        parseJob = null
        mutableModel.value = null
    }
}
