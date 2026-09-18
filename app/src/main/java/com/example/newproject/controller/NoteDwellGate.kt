package com.example.newproject.controller

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ノートを開いてから**続けて [dwellMillis] 表示された**ことを待つ門番。
 * 自動で走る生成（要約・分野判定・再会カードの要約・関連ノートのAI推薦）は、
 * Nano を呼ぶ直前に [await] を通す（→ `docs/dev/system/background_ai_ux.md` §7）。
 *
 * **待たせるのは生成の呼び出しだけ。** 保存済みの要約・生の再会カードなど、
 * AIを呼ばずに出せるものは門番の手前で出す。
 *
 * **一度通ったら、そのノートのあいだは開いたまま。** 通った後に冊子や背面へ回っても閉じない
 * （走り始めた生成を止めるのはノート切替だけ）。
 *
 * **メインスレッドからだけ触る。** 状態を錠で守っていないのは、
 * [start]・[stop]・[pause]・[resume] と [await] の呼び出しがすべて Main に載っているため。
 */
internal class NoteDwellGate(
    /** 待ち時間を数えるタイマーを載せる。**ノート単位のジョブと同じスコープ**を渡す。 */
    private val scope: CoroutineScope,
    private val dwellMillis: Long = DWELL_MILLIS
) {
    /**
     * いまのノートの門。**[stop] で取り消して作り直す。**
     *
     * 取り消した門を待っていた呼び出しは `CancellationException` で抜ける。
     * 開いたままにすると、旧ノートの生成を次のノートの門が通してしまう。
     */
    private var passed = CompletableDeferred<Unit>()

    /** 本文が出たか。**出る前に停止理由が解けても数え始めない。** */
    private var started = false

    private var timer: Job? = null

    /**
     * 待ち時間を止めている理由。**ノートをまたいで残す**（→ [ReadingPauseReason]）。
     * 冊子を見たまま、または背面のままノートが切り替わる経路があるため。
     */
    private val pauseReasons = mutableSetOf<ReadingPauseReason>()

    /** ノート本文を表示した。**停止理由が無ければ**待ち時間を数え始める。 */
    fun start() {
        started = true
        arm()
    }

    /** ノートを離れた。待っている生成をすべて取り消し、次のノートは最初から数える。 */
    fun stop() {
        timer?.cancel()
        timer = null
        passed.cancel()
        passed = CompletableDeferred()
        started = false
    }

    /**
     * 冊子が前面に来た・アプリが背面へ回った。**数えかけの時間は捨てる。**
     *
     * 足し算で続きから数えない。「留まっている」は今いることであって、
     * 冊子をめくって戻ってきた直後に生成を始めるためのものではない。
     */
    fun pause(reason: ReadingPauseReason) {
        pauseReasons += reason
        timer?.cancel()
        timer = null
    }

    /** 停止理由が1つ解けた。**全部解けたときだけ**、最初から数え直す。 */
    fun resume(reason: ReadingPauseReason) {
        pauseReasons -= reason
        arm()
    }

    /** 門が開くまで待つ。ノートを離れたら `CancellationException` で抜ける。 */
    suspend fun await() {
        passed.await()
    }

    private fun arm() {
        if (!started || pauseReasons.isNotEmpty()) return
        // **数えている最中なら2本目を立てない。** 背面からの復帰は停止していなくても届く（起動直後の onStart）。
        // 2本立てると [pause] が止めるのは新しい方だけで、古い方が冊子を見ている間に門を開ける。
        if (timer?.isActive == true) return
        val gate = passed
        timer = scope.launch {
            delay(dwellMillis)
            gate.complete(Unit)
        }
    }

    companion object {
        /**
         * 留まったとみなす時間。読書痕跡の訪問条件（10秒）より短くする —
         * こちらは読んだかどうかではなく、**すぐ離れるノートに Nano を使わない**ための線である。
         */
        const val DWELL_MILLIS = 3_000L
    }
}
