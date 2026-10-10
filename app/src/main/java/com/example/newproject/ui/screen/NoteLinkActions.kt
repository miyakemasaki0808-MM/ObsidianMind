package com.example.newproject.ui.screen

import com.example.newproject.domain.NoteLinkResolution
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
        is NoteLinkResolution.Missing -> NoteLinkAction.Notice("「${resolution.label}」は見つかりませんでした")
        NoteLinkResolution.NotReady -> NoteLinkAction.Notice("ノートの一覧を読み込んでいます。少ししてからもう一度押してください")
    }

/**
 * 見出しが見つからなかったときの知らせ。[opened] は別のノートを開いたか — 開いたなら先頭から始めたことも伝える。
 * 同じノートの中なら本文は動かさない。
 */
internal fun noteLinkHeadingMissingText(heading: String, opened: Boolean): String =
    if (opened) "見出し「$heading」が見つからないので、先頭から開きました"
    else "見出し「$heading」が見つかりませんでした"
