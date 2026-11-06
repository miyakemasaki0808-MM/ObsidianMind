package com.example.newproject.domain

import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.SectionChatState

/** 見出しの要約ボタンが示す、全節の要約の状態。[Working.done] は要約ができた節の数、[Working.total] は作る節の数。 */
internal sealed interface SectionBatchStatus {
    data object Idle : SectionBatchStatus

    data class Working(val done: Int, val total: Int) : SectionBatchStatus

    /** 止まっていて、まだ作れる節がある（上限・混み合い・止める操作）。押すと続きから作る。 */
    data class Partial(val done: Int, val total: Int) : SectionBatchStatus

    data object Ready : SectionBatchStatus

    /** 出せなかった節がある。押すと作り直す。 */
    data object Error : SectionBatchStatus
}

/**
 * 全節の要約から [SectionBatchStatus] を導出する。頼んでいなければ Idle。
 *
 * **進み具合は対象の節と要約から数える**ので、数と実物がずれない。**端末AIが使えないだけなら全節は残らない**
 * （→ `SectionChatController`）ので、ここに理由の状態は無い。
 */
internal fun sectionBatchStatus(chat: SectionChatState): SectionBatchStatus {
    val batch = chat.batch ?: return SectionBatchStatus.Idle
    val made = chat.summaries.associateBy { it.section }
    val targets = batch.targets.map { made[it] }
    val done = targets.count { it?.summary != null }
    return when {
        batch.isRunning -> SectionBatchStatus.Working(done, targets.size)
        targets.any { it?.summaryProblem is SectionChatProblem.GenerationFailed } -> SectionBatchStatus.Error
        done == targets.size -> SectionBatchStatus.Ready
        else -> SectionBatchStatus.Partial(done, targets.size)
    }
}

/** 押すのが見に行く操作か（→ `summaryEntryFor`）。生成中と作り終えた後は作るものが無い。 */
internal fun SectionBatchStatus.showsResultOnPress(): Boolean =
    this is SectionBatchStatus.Working || this == SectionBatchStatus.Ready

/** 見出しの要約ボタンの記号。状態はボタンの上で静かに示し、浮く通知は作らない。 */
internal fun sectionBatchEntrySymbol(status: SectionBatchStatus): String = when (status) {
    SectionBatchStatus.Idle, is SectionBatchStatus.Partial -> "💬"
    is SectionBatchStatus.Working -> "⏳"
    SectionBatchStatus.Ready -> "✓"
    SectionBatchStatus.Error -> "!"
}

/** 記号に添える進み具合。**節が1つなら数えない** — 「0/1」は押した本人に何も足さない。 */
internal fun sectionBatchEntryCount(status: SectionBatchStatus): String? = when (status) {
    is SectionBatchStatus.Working -> "${status.done}/${status.total}".takeIf { status.total > 1 }
    is SectionBatchStatus.Partial -> "${status.done}/${status.total}".takeIf { status.total > 1 }
    SectionBatchStatus.Idle, SectionBatchStatus.Ready, SectionBatchStatus.Error -> null
}

/** 見出しの要約ボタンの読み上げ名。**「全節」を核にする** — 今の節だけの入口は面の「この節を要約」が持つ。 */
internal fun sectionBatchEntryDescription(status: SectionBatchStatus): String = when (status) {
    SectionBatchStatus.Idle -> "全節を要約"
    is SectionBatchStatus.Working -> "全節を要約中。${status.total}節中${status.done}節まで。タップで開く"
    is SectionBatchStatus.Partial -> "全節の要約。${status.total}節中${status.done}節まで。タップで続きを作る"
    SectionBatchStatus.Ready -> "全節の要約あり。タップで開く"
    SectionBatchStatus.Error -> "全節の要約に出せなかった節あり。タップで作り直す"
}

/** 面の要約の行に添える、全節の進み具合と次の行動。**数字だけの記号が何を指すかを、ここで言葉にする。** */
internal fun sectionBatchNote(chat: SectionChatState): String? = when (val status = sectionBatchStatus(chat)) {
    is SectionBatchStatus.Working -> "全節を要約しています（${status.total}節中${status.done}節）"
    is SectionBatchStatus.Partial ->
        if (chat.batch?.stoppedBusy == true) {
            "端末のAIが混み合っています。少し待って 💬 を押すと、続きを作ります（${status.total}節中${status.done}節）"
        } else {
            "${status.total}節中${status.done}節を要約しました。💬 を押すと続きを作ります"
        }
    SectionBatchStatus.Error -> "出せなかった節があります。! を押すと作り直します"
    SectionBatchStatus.Idle, SectionBatchStatus.Ready -> null
}
