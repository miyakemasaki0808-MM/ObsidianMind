package com.example.newproject.ui.screen

import com.example.newproject.domain.NoteLinkResolution
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.RelatedNote

/** 本文のリンクを押した後に画面がすること（→ features/note_links.md §5 の画面遷移）。 */
internal sealed interface NoteLinkAction {
    /** 今の本文をその見出しへ送る。null なら先頭へ。 */
    data class ScrollCurrent(val heading: String?) : NoteLinkAction

    /**
     * [note] を開く。[onSupporting] なら補助の面で眺め、そうでなければ今のノートにする。
     * 見出しがあれば、その見出しから始める。
     */
    data class Open(val note: RelatedNote, val heading: String?, val onSupporting: Boolean) : NoteLinkAction

    /** 何も開かず、知らせだけを出す。 */
    data class Notice(val text: String) : NoteLinkAction
}

/**
 * 引いた結果と窓から、画面の操作を決める。**開く面は窓で決め、端末で決めない**（→ features/note_links.md §8 判断1）。
 *
 * [canShowSupporting] は補助の面を出せる窓か。ペインを閉じていても、出せる窓なら補助の面で開く。
 * 全画面は面が1つなので false を渡す。
 */
internal fun noteLinkActionFor(resolution: NoteLinkResolution, canShowSupporting: Boolean): NoteLinkAction =
    when (resolution) {
        is NoteLinkResolution.InCurrent -> NoteLinkAction.ScrollCurrent(resolution.heading)
        is NoteLinkResolution.Open -> NoteLinkAction.Open(resolution.note, resolution.heading, canShowSupporting)
        is NoteLinkResolution.Missing -> NoteLinkAction.Notice(noteLinkMissingText(resolution.label))
        NoteLinkResolution.NotReady -> NoteLinkAction.Notice(NOTE_LINK_NOT_READY_TEXT)
    }

/** 右（補助の面）のノートの中のリンクを押した後に画面がすること（→ features/desk_foundation.md §5）。 */
internal sealed interface SideLinkAction {
    /** 右のノートをその見出しへ送る。null なら先頭へ。 */
    data class ScrollSide(val heading: String?) : SideLinkAction

    /** 左の今のノートをその見出しへ送る。null なら先頭へ。 */
    data class ScrollMain(val heading: String?) : SideLinkAction

    /** 右を [note] に替える。見出しがあれば、その見出しから始める。 */
    data class OpenSide(val note: RelatedNote, val heading: String?) : SideLinkAction

    /** 何も開かず、知らせだけを出す。 */
    data class Notice(val text: String) : SideLinkAction
}

/**
 * 右のノートを出発点に引いた結果から、画面の操作を決める。**右で辿っても今のノートにはしない**（→ system/fold_experience.md §4）。
 *
 * 今のノート [currentNote] へのリンクは右へ開かず、左の本文を送る。左に出ているノートを右にも出すと、
 * 右の「このノートへ移る」が今のノートを開き直すことになる。
 */
internal fun sideLinkActionFor(resolution: NoteLinkResolution, currentNote: DocumentRef?): SideLinkAction =
    when (resolution) {
        is NoteLinkResolution.InCurrent -> SideLinkAction.ScrollSide(resolution.heading)
        is NoteLinkResolution.Open ->
            if (resolution.note.ref == currentNote) SideLinkAction.ScrollMain(resolution.heading)
            else SideLinkAction.OpenSide(resolution.note, resolution.heading)
        is NoteLinkResolution.Missing -> SideLinkAction.Notice(noteLinkMissingText(resolution.label))
        NoteLinkResolution.NotReady -> SideLinkAction.Notice(NOTE_LINK_NOT_READY_TEXT)
    }

private fun noteLinkMissingText(label: String): String = "「$label」は見つかりませんでした"

private const val NOTE_LINK_NOT_READY_TEXT = "ノートの一覧を読み込んでいます。少ししてからもう一度押してください"

/**
 * 見出しが見つからなかったときの知らせ。[opened] は別のノートを開いたか — 開いたなら先頭から始めたことも伝える。
 * 同じノートの中なら本文は動かさない。
 */
internal fun noteLinkHeadingMissingText(heading: String, opened: Boolean): String =
    if (opened) "見出し「$heading」が見つからないので、先頭から開きました"
    else "見出し「$heading」が見つかりませんでした"
