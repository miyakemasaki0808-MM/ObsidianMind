package com.example.newproject.domain

import com.example.newproject.model.NOTE_FIELD_NONE_ID
import com.example.newproject.model.NoteField
import com.example.newproject.model.promptId

/**
 * 分野判定のAI応答を読んだ結果。
 *
 * **「該当なし」と「読めなかった」を型で分ける**（→ `docs/dev/features/note_field_color.md` 判断13）。
 * 混ぜると、正常な該当なしを生成失敗として**再試行し続ける**か、
 * 逆に不正応答を確定として**保存してしまう**かのどちらかになる。
 */
sealed interface NoteFieldAnswer {

    /** 候補から1つ選ばれた。**確定として保存してよい。** */
    data class Chosen(val field: NoteField) : NoteFieldAnswer

    /** どれにも当たらない、という**正常な答え**。`確定（分野なし）`として保存し、無彩色にする。 */
    data object NoneOfThem : NoteFieldAnswer

    /** 読めなかった。**保存しない。**空・未知のID・複数候補がここへ来る。 */
    data object Invalid : NoteFieldAnswer
}

/**
 * 応答から分野を1つ読む。
 *
 * ## 受け付ける形
 *
 * **応答全体がID行だけでできていること。** 見るのは `F1`..`F6` と `NONE` で、
 * 空行・箇条書き記号・引用符・コードフェンスは装飾として読み飛ばす。
 *
 * ## 落とす形
 *
 * **1つに定まらなければ [NoteFieldAnswer.Invalid]。** 0個でも2個以上でも落とす。
 * 「多数決を採る」「最初の1つを採る」をやらないのは、**どちらも根拠が無い**からである —
 * AIが迷った応答を、こちらが勝手に決めて確定として永続すると、
 * **入力版が同じあいだ二度と直らない。** 落とせば次に開いたときやり直せる。
 *
 * **ID以外の行が1つでもあれば、拾えた候補ごと落とす**（→ レビュー P2-2）。
 * 読めない行を読み飛ばすと、`F1` の次行に `F1 ではなく F3 が適切です` を返す応答から
 * **否定されたほうのF1を確定する。** 装飾と「選択を無効にする内容」は同じ扱いにできない。
 *
 * **同じIDの繰り返しは1つと数える。** `F1\nF1` は迷いではなく、単に整形が崩れただけである。
 */
fun parseNoteFieldAnswer(response: String): NoteFieldAnswer {
    val byId = NoteField.entries.associateBy { it.promptId }
    val found = LinkedHashSet<String>()
    for (raw in response.lineSequence()) {
        val trimmed = raw.trim()
        // コードフェンスの行は丸ごと装飾。**情報文字列（```text）が付いても同じ** —
        // 剥がした残りを答えとして読むと、フェンスの書き方ひとつで応答全体が落ちる。
        if (trimmed.startsWith(CODE_FENCE)) continue
        val line = trimmed
            .removePrefix("-").removePrefix("*").removePrefix("•")
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
        if (line.isEmpty()) continue
        // **行全体がIDの形であることを要求し、外れた行は応答ごと落とす。**
        // 先頭だけを見ると `F1 / F3`（併記）や `F1 ではなく F3 が適切です`（否定）から
        // 先頭のIDを取って確定し（→ レビュー P2-3）、読み飛ばすと同じ文が次の行に来たときに
        // 素通りする（→ レビュー P2-2）。
        val token = ANSWER_LINE.matchEntire(line)?.groupValues?.get(1)?.uppercase()
            ?: return NoteFieldAnswer.Invalid
        found += token
    }
    val only = found.singleOrNull() ?: return NoteFieldAnswer.Invalid
    if (only == NOTE_FIELD_NONE_ID) return NoteFieldAnswer.NoneOfThem
    return byId[only]?.let(NoteFieldAnswer::Chosen) ?: NoteFieldAnswer.Invalid
}

private const val CODE_FENCE = "```"

/**
 * **行の全体**がIDであること。
 *
 * `F` の後は1桁で、`F12` や `Field` は弾く。**末尾の句読点だけは許す** —
 * 整形の癖であって別の答えではない。
 *
 * **「先頭がIDなら通す」にはしない。** それだと `F1 / F3` も `F1 ではなく F3` も
 * 先頭のF1として確定し、**曖昧な応答と明示的に否定された分野が保存される。**
 * 指示に従っていない応答は落として、次に開いたときやり直せばよい。
 */
private val ANSWER_LINE =
    Regex("""(F\d|NONE)(?![0-9A-Za-z])[.,:;。、]?""", RegexOption.IGNORE_CASE)
