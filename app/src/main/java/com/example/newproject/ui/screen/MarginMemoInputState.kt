package com.example.newproject.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.newproject.domain.MarginMemoInput
import com.example.newproject.model.state.MarginMemoState

/** シートとペインへ渡す書きかけ。[onSubmit] は保存を頼む**直前**に呼ぶ。 */
internal class MarginMemoInputBinding(
    val text: String,
    val onChange: (String) -> Unit,
    val onSubmit: (String) -> Unit
)

/** 画面の保存値へ一組ごと残す。**ノートの識別も一緒に残す**のが要点（→ [MarginMemoInput]）。 */
internal val MarginMemoInputSaver: Saver<MarginMemoInput, Any> = listSaver(
    save = { listOf(it.noteKey, it.text, it.submitted, it.seenAcceptedCount) },
    restore = {
        MarginMemoInput(
            noteKey = it[0] as String?,
            text = it[1] as String,
            submitted = it[2] as String?,
            seenAcceptedCount = it[3] as Long
        )
    }
)

/**
 * 余白メモの書きかけを、**シートとペインの外**で持つ。✎ でペインをしまう・Fold を閉じてシートへ移る・
 * 画面の作り直し・ほかのタブとの往復をまたいで、文字と受理の対応を一緒に保つ。
 *
 * 表示部品が無い間に届いた受理も、次に組み立てたときの照合で消費される。
 */
@Composable
internal fun rememberMarginMemoInput(noteKey: String?, memoState: MarginMemoState): MarginMemoInputBinding {
    var stored by rememberSaveable(stateSaver = MarginMemoInputSaver) { mutableStateOf(MarginMemoInput(noteKey)) }
    val accepted = (memoState as? MarginMemoState.Ready)?.acceptedCount ?: 0L
    val current = stored.reconciled(noteKey, accepted)
    SideEffect { if (stored != current) stored = current }
    return MarginMemoInputBinding(
        text = current.text,
        onChange = { text -> stored = stored.reconciled(noteKey, accepted).edited(text) },
        onSubmit = { text -> stored = stored.reconciled(noteKey, accepted).submitting(text) }
    )
}
