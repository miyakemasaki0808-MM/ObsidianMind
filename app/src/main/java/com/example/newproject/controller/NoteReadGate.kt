package com.example.newproject.controller

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ノートを**足し算で [readMillis] 読んだ**ことを待つ線。結晶の材料を控える前に通す
 * （→ `docs/dev/features/reflect_crystal.md` 判断4）。
 *
 * **[NoteDwellGate] とは数え方が違う。** あちらは「今いるか」なので冊子や背面から戻ると最初から数え直し、
 * こちらは「どれだけ読んだか」なので止めている間だけ数えず、戻ったら続きから足す（読書痕跡の10秒と同じ数え方）。
 * 数え直すと、通知で一瞬離れただけで30秒をやり直すことになる。
 *
 * **一度通ったら、そのノートのあいだは開いたまま。** ノートを離れたら取り消し、次のノートは0から数える。
 *
 * **メインスレッドからだけ触る。** [NoteDwellGate] と同じく、呼び出しがすべて Main に載っている。
 */
internal class NoteReadGate(
    /** 待ち時間を数えるタイマーを載せる。**ノート単位のジョブと同じスコープ**を渡す。 */
    private val scope: CoroutineScope,
    /** 単調に進む時計（ミリ秒）。止めた区間の長さを測る。壁時計は時刻合わせで戻りうるので使わない。 */
    private val elapsedMillis: () -> Long,
    private val readMillis: Long = READ_MILLIS
) {
    /** いまのノートの門。**[stop] で取り消して作り直す**（旧ノートの試行を次のノートの門が通さない）。 */
    private var passed = CompletableDeferred<Unit>()

    /** 本文が出たか。**出る前に停止理由が解けても数え始めない。** */
    private var started = false

    /** 止めた区間までに読んだ時間。 */
    private var accumulated = 0L

    /** 今の区間を数え始めた時刻。止めている間・数えていない間は null。 */
    private var runningSince: Long? = null

    private var timer: Job? = null

    /** 数えるのを止めている理由。**ノートをまたいで残す**（→ [ReadingPauseReason]）。 */
    private val pauseReasons = mutableSetOf<ReadingPauseReason>()

    /** ノート本文を表示した。**停止理由が無ければ**数え始める。 */
    fun start() {
        started = true
        count()
    }

    /** ノートを離れた。待っている試行を取り消し、読んだ時間を0に戻す。 */
    fun stop() {
        timer?.cancel()
        timer = null
        passed.cancel()
        passed = CompletableDeferred()
        started = false
        accumulated = 0L
        runningSince = null
    }

    /** 冊子が前面に来た・アプリが背面へ回った。**ここまで読んだ時間は残す。** */
    fun pause(reason: ReadingPauseReason) {
        pauseReasons += reason
        runningSince?.let { accumulated += elapsedMillis() - it }
        runningSince = null
        timer?.cancel()
        timer = null
    }

    /** 停止理由が1つ解けた。**全部解けたときだけ**、続きから数える。 */
    fun resume(reason: ReadingPauseReason) {
        pauseReasons -= reason
        count()
    }

    /** 読み終えるまで待つ。ノートを離れたら `CancellationException` で抜ける。 */
    suspend fun await() {
        passed.await()
    }

    private fun count() {
        if (!started || pauseReasons.isNotEmpty() || passed.isCompleted) return
        // **数えている最中なら区間を始め直さない。** 背面からの復帰は止めていなくても届く（起動直後の onStart）。
        // 始め直すと、それまでの区間の時間を足さずに捨てる。
        if (runningSince != null) return
        runningSince = elapsedMillis()
        val gate = passed
        val remaining = (readMillis - accumulated).coerceAtLeast(0L)
        timer = scope.launch {
            delay(remaining)
            gate.complete(Unit)
        }
    }

    companion object {
        /** 読んだとみなす時間。実機で様子を見て決め直す値（→ reflect_crystal.md 判断4）。 */
        const val READ_MILLIS = 30_000L
    }
}
