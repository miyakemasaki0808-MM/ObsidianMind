package com.example.newproject.controller

import com.example.newproject.model.BesideNote

/**
 * 並べた相手ごとに、右に資料として見えていた時間を足す（→ features/desk_memory.md §5・判断4・判断5）。
 * 1回の読書（[ReadingTraceController] の読書の区間）と同じ寿命で、ノートを替えれば作り直す。
 *
 * **数えるのは [show] で相手が見えていて、[pause] で止めていない間だけ。** 見えているかは画面が知っているので、
 * 見えなくなったら `show(null)` が届く。止める理由（背面・冊子）は読書の区間と同じ口から届く。
 *
 * 時計は単調に進むもの（[NoteReadGate] と同じ）。相手は相対パスで見分ける — 開いた入口でタイトルが違っても同じノートである。
 */
internal class BesideTally(
    private val elapsedMillis: () -> Long,
    private val thresholdMillis: Long = NoteReadGate.READ_MILLIS
) {
    private class Entry(var note: BesideNote, var totalMillis: Long, var shownOrder: Long)

    private val entries = mutableMapOf<String, Entry>()
    private var shown: Entry? = null

    /** 今の区間を数え始めた時刻。見えていない間・止めている間は null。 */
    private var runningSince: Long? = null
    private var paused = false
    private var order = 0L

    /** 右に見えている相手。見えていない・候補にならないなら null。 */
    fun show(note: BesideNote?) {
        settle()
        shown = note?.let { candidate ->
            entries.getOrPut(candidate.vaultRelativePath) { Entry(candidate, 0L, 0L) }.also {
                it.note = candidate
                it.shownOrder = ++order
            }
        }
        start()
    }

    fun pause() {
        settle()
        paused = true
    }

    fun resume() {
        if (!paused) return
        paused = false
        start()
    }

    /**
     * 線（[thresholdMillis]）を越えた相手のうち、いちばん長く見えていたもの。同じ長さなら後から並べたもの。
     * 越えた相手が無ければ null。**今の区間も数える**ので、見えている最中に離れても足りない分を落とさない。
     */
    fun leader(): BesideNote? {
        val now = elapsedMillis()
        return entries.values
            .map { entry -> entry to entry.totalMillis + (runningSince?.takeIf { entry === shown }?.let { now - it } ?: 0L) }
            .filter { (_, millis) -> millis >= thresholdMillis }
            .maxWithOrNull(compareBy<Pair<Entry, Long>> { it.second }.thenBy { it.first.shownOrder })
            ?.first
            ?.note
    }

    private fun settle() {
        val since = runningSince ?: return
        shown?.let { it.totalMillis += elapsedMillis() - since }
        runningSince = null
    }

    private fun start() {
        runningSince = if (shown != null && !paused) elapsedMillis() else null
    }
}
