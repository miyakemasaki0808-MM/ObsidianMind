package com.example.newproject.ui.screen

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import com.example.newproject.ui.theme.OnSurfaceFaint

/**
 * 余白メモの入力欄が持つ編集の状態（文字・カーソルと選択・変換中の範囲）。**面の外でノートごとに持てば、面が外れて組み直されても保つ**
 * （→ features/margin_quote.md §5.6）。文字の正本は書きかけで、ここは入力欄の側の写し。
 */
@Stable
internal class MemoField(initial: String = "") {
    val state = TextFieldState(initial)

    /** 入力欄と書きかけが最後にそろった文字。どちら側が変わったかを、これと比べて決める（→ [memoFieldEdit]・[memoFieldReset]）。 */
    var synced: String = initial
        private set

    /** 入力欄の文字 [text] が変わっていれば書きかけへ渡す。 */
    fun edited(text: String, onEdit: (String) -> Unit) {
        val edit = memoFieldEdit(text, synced) ?: return
        synced = edit
        onEdit(edit)
    }

    /**
     * 書きかけの文字 [draft] が外で変わっていれば（受け取れて空になった・ノートを替えた）、入力欄をそれへ合わせてカーソルを末尾へ置く。
     * この書き換えは元に戻す履歴も消す（Compose Foundation 1.7.3 の `TextFieldState.edit`）ので、置いて受け取れた文字を元に戻す操作で呼び戻さない。
     */
    fun follow(draft: String) {
        memoFieldReset(state.text.toString(), draft, synced)?.let(state::setTextAndPlaceCursorAtEnd)
        synced = draft
    }
}

/** 入力欄で変わった文字 [text] を書きかけへ渡すなら、その文字。最後にそろった文字 [synced] と同じなら null（カーソル・選択だけの変更も同じ）。 */
internal fun memoFieldEdit(text: String, synced: String): String? = text.takeIf { it != synced }

/**
 * 書きかけが外で変わったときに入力欄へ入れる文字。書きかけ [draft] が最後にそろった文字 [synced] と同じなら null（外では変わっていない）、
 * 入力欄 [field] がもう同じ文字なら null（カーソルを動かさない）。
 */
internal fun memoFieldReset(field: String, draft: String, synced: String): String? =
    draft.takeIf { it != synced && it != field }

/**
 * 余白メモの入力欄。見た目は Material の枠つきの入力欄と同じにし、文字・カーソル・選択は [field] が持つ。
 * **利用者の編集は状態へ直接入る** — `TextFieldValue` 版の入力欄は描いた値と同じ通知を捨てるので、描き直しの前に足して戻すと
 * 最後の編集が届かない（→ features/margin_quote.md §5.6）。
 */
// 枠と案内の描き方（OutlinedTextFieldDefaults.DecorationBox）は Material3 1.3 では試験的な API。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoInputField(
    field: MemoField,
    draftText: String,
    onEdit: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val currentOnEdit by rememberUpdatedState(onEdit)
    // 利用者の編集は変換の口で同期して渡す。次の描画を待つと、その間に押した「置く」が前の文字で送る。
    val transformation = remember(field) {
        object : InputTransformation {
            override fun TextFieldBuffer.transformInput() = field.edited(asCharSequence().toString(), currentOnEdit)
        }
    }
    // **元に戻す・やり直すは変換の口を通らない**（Compose Foundation 1.7.3 で確認）。状態の変化を見張って渡す。
    LaunchedEffect(field) {
        snapshotFlow { field.state.text.toString() }.collect { field.edited(it, currentOnEdit) }
    }
    SideEffect { field.follow(draftText) }

    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = OutlinedTextFieldDefaults.colors()
    val textColor = if (focused) colors.focusedTextColor else colors.unfocusedTextColor
    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
        BasicTextField(
            state = field.state,
            modifier = modifier.defaultMinSize(
                minWidth = OutlinedTextFieldDefaults.MinWidth,
                minHeight = OutlinedTextFieldDefaults.MinHeight
            ),
            inputTransformation = transformation,
            textStyle = LocalTextStyle.current.merge(TextStyle(color = textColor)),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 6),
            interactionSource = interactionSource,
            cursorBrush = SolidColor(colors.cursorColor),
            decorator = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = field.state.text.toString(),
                    innerTextField = innerTextField,
                    enabled = true,
                    singleLine = false,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = interactionSource,
                    placeholder = { Text(placeholder, color = OnSurfaceFaint) },
                    colors = colors
                )
            }
        )
    }
}
