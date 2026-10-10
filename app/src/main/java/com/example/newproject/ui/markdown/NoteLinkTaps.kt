package com.example.newproject.ui.markdown

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.example.newproject.domain.NoteLink

/**
 * 本文のリンクを押したときの口（→ features/note_links.md §7）。**リンクを押せるのは、これを渡された本文だけ。**
 * 補助の面の本文には渡さないので、そちらのリンクは今と同じ押せない文字列のまま描かれる。
 */
internal class NoteLinkTaps(private val onLink: (NoteLink) -> Unit) {
    /**
     * 今の指離しが長押しの後か。[watchLinkPresses] が指離しの Initial の段で立て、同じ指離しの Final の段で下ろす。
     * リンクが「押した」を受け取るのは、その間の Main の段である。
     */
    internal var heldTooLong = false

    /** リンクが押された。長押しの後の指離しなら開かない — 長押しは文字の選択に使われている。 */
    fun open(link: NoteLink) {
        if (!heldTooLong) onLink(link)
    }
}

/** 本文の中で押せるリンクの口。`null` なら、リンクは押せない文字列のまま描く。 */
internal val LocalNoteLinkTaps = staticCompositionLocalOf<NoteLinkTaps?> { null }

/**
 * 長押しの後の指離しでリンクを開かせない見張り（→ features/note_links.md §8 判断4）。本文のリンクより外側の層に付ける。
 * **`SelectionContainer` の修飾子には付けない** — 押下の途中で作り直され、指離しを受け取れない（→ `MarkdownNoteContent`）。
 *
 * Compose 1.7.3 のリンクはリンクごとに押せる箱を重ね、長押しを受け取らないので、長く押してから離しても「押した」になる。
 * 文字の選択は長押しで始まるので、そのままではリンクの上から選ぼうとした指を離した瞬間にノートが替わる。
 * **容器は子より先に指離しを見る**（Initial の段）ので、そこで押していた時間を測って [NoteLinkTaps] に知らせる。
 * 指は使わない — 選択・スクロール・横の払いの判定には触れない。
 */
internal fun Modifier.watchLinkPresses(taps: NoteLinkTaps): Modifier = pointerInput(taps) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                .firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed) {
                taps.heldTooLong = change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                // 同じ指離しの Final の段。リンクはこの前の Main の段で「押した」を受け取り終えている。
                awaitPointerEvent(PointerEventPass.Final)
                taps.heldTooLong = false
                return@awaitEachGesture
            }
        }
    }
}
