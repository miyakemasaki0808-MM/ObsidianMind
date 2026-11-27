package com.example.newproject.domain

import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.model.NoteForm

/**
 * ノートの型を、解析済みの本文と frontmatter の `cssclasses` だけから決める（→ `docs/dev/features/note_typesetting.md` §5.9）。
 *
 * 上から順に当て、最初に当たったものに決める。
 * 1. `cssclasses` に型の名前があれば従う
 * 2. 見出しの骨格: 一方の型の語に当たる見出しが2つ以上あり、もう一方より多ければその型
 * 3. どれにも当たらなければ、コードの枠を持つノートは技術書、持たないノートは標準
 *
 * **AI の結果・痕跡・ファイルの場所を入力にしない。** 型は表示の前に決まる必要があり、開いた後に届く値で決めると紙面が組み替わる。
 * 本文の長さに比例するので、本文の解析と同じく Main の外で呼ぶ。
 */
internal fun noteFormOf(cssClasses: List<String>, blocks: List<MarkdownBlock>): NoteForm {
    noteFormOfCssClasses(cssClasses)?.let { return it }
    var techBook = 0
    var readingNote = 0
    for (block in blocks) {
        if (block !is MarkdownBlock.Heading) continue
        val word = headingWord(block.text)
        when (word) {
            in TECH_BOOK_HEADINGS -> techBook++
            in READING_NOTE_HEADINGS -> readingNote++
        }
    }
    if (techBook >= SKELETON_MIN_HEADINGS && techBook > readingNote) return NoteForm.TechBook
    if (readingNote >= SKELETON_MIN_HEADINGS && readingNote > techBook) return NoteForm.ReadingNote
    return if (blocks.any { it is MarkdownBlock.CodeBlock }) NoteForm.TechBook else NoteForm.Standard
}

/** `cssclasses` の中で最初に型の名前に当たったもの。英字の大小は見ない。 */
private fun noteFormOfCssClasses(cssClasses: List<String>): NoteForm? =
    cssClasses.firstNotNullOfOrNull { CSS_CLASS_FORMS[it.trim().lowercase()] }

/**
 * 見出しから、骨格の辞書と照らす語を取り出す。**頭と末尾の、文字でない記号（絵文字・番号・約物・強調の記号）を外す。**
 * 中の記号は外さない（「解説・注意点」は1つの語）。照らすのは完全一致なので、ここで外し過ぎると別の見出しが辞書に当たる。
 */
internal fun headingWord(text: String): String {
    var start = 0
    while (start < text.length) {
        val codePoint = text.codePointAt(start)
        if (Character.isLetter(codePoint)) break
        start += Character.charCount(codePoint)
    }
    var end = text.length
    while (end > start) {
        val codePoint = text.codePointBefore(end)
        if (Character.isLetter(codePoint)) break
        end -= Character.charCount(codePoint)
    }
    return text.substring(start, end)
}

private const val SKELETON_MIN_HEADINGS = 2

private val CSS_CLASS_FORMS: Map<String, NoteForm> = mapOf(
    "techbook" to NoteForm.TechBook,
    "技術書" to NoteForm.TechBook,
    "readingnote" to NoteForm.ReadingNote,
    "読書ノート" to NoteForm.ReadingNote,
    "standard" to NoteForm.Standard,
    "標準" to NoteForm.Standard
)

/**
 * 技術書の骨格の見出し。Vault の実物の見出しから取った（→ `docs/dev/features/note_typesetting.md` §5.9）。
 * **両方の型にまたがる語（「関連」「まとめ」など）は入れない。** 入れると、片方の骨格のノートがもう片方へ数えられる。
 */
private val TECH_BOOK_HEADINGS: Set<String> = setOf(
    "目的",
    "構文",
    "基本構文",
    "基本構文と使用例",
    "ユースケース",
    "ユースケースに沿った構文例",
    "実践コード全文",
    "使い方",
    "注意点",
    "解説・注意点",
    "レベル",
    "関連知識"
)

/** 読書ノートの骨格の見出し。技術書と同じく、両方の型にまたがる語は入れない。 */
private val READING_NOTE_HEADINGS: Set<String> = setOf(
    "概要",
    "紹介文",
    "要点",
    "要点メモ",
    "章全体の要約",
    "印象に残った内容",
    "分からなかった点",
    "次回読む範囲",
    "感想",
    "コメント",
    "読書レポート"
)
