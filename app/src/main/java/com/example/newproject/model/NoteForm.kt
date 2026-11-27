package com.example.newproject.model

/**
 * ノートの型。**読む面の書体と行間のチャネルが表すもの**（→ `docs/dev/system/bearing_channels.md` §11）。
 *
 * 型は見出しの骨格とコードの枠で決まる**書き方の形**で、分野（[NoteField]）ではない。
 * 型で変えるのはプレートの中の組み方だけで、地の色とプレートの形は型によらない
 * （→ `docs/dev/features/note_typesetting.md` 判断2）。
 */
enum class NoteForm {
    /** 型に意味のある部品を持たない。プレートと型に関係ない部品だけで組む。 */
    Standard,

    /** 目的・構文・注意点の骨格か、コードの枠を持つノート。 */
    TechBook,

    /** 概要・要点メモ・コメントの骨格を持つノート。 */
    ReadingNote
}
