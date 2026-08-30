package com.example.newproject.domain

import com.example.newproject.model.ReunionKind

// ---------------------------------------------------------------------------
// 再会カードに出す1件の「候補列挙」。**規則だけで列挙し、選別だけをAIに任せる。**
//
// 列挙を正規表現で行うのは速度のためだけではない。AIへ渡す本文は抜粋なので、
// 長文では切り落とされた区間の問いが届かない。**規則なら原文全体に当てられる**ので、
// 「抜粋に無いものは選べない」という制約を列挙側で外す
// （→ features/reunion_card.md「候補の列挙は原文全体へ当てる」）。
//
// 純関数だが**軽くはない**（入力サイズに比例する）。呼び出し側は Dispatchers.Default
// へ逃がすこと（→ lessons L13）。
// ---------------------------------------------------------------------------

/** 種別ごとにAIへ渡す候補の上限。多く見せても選ばせる1件は増えない。 */
internal const val REUNION_CANDIDATES_PER_KIND = 10

/**
 * カードは1文で伝える役目なので、長すぎる文は候補にしない。
 *
 * **下限は 8。** 10 にしていたら「本当にそうなのか。」（9文字）が落ちた。
 * 日本語の問いは短くなりやすく、短いこと自体は価値を下げない。
 * 落としたいのは「そうか。」のような**それ単独では何も思い出せない断片**なので、そこだけを切る。
 */
private const val MIN_CANDIDATE_CHARS = 8
private const val MAX_CANDIDATE_CHARS = 120

internal data class ReunionCandidates(
    val questions: List<String>,
    val stalenessMarks: List<String>
) {
    companion object {
        val EMPTY = ReunionCandidates(emptyList(), emptyList())
    }
}

/**
 * 種別を決める。**AIに順位を決めさせない** — どの種別で生成するかを呼ぶ前に確定させることで、
 * 生成は常に1回で済み、判定は純関数としてJVMで固定できる（→ features/reunion_card.md §5）。
 *
 * 印（「まだ考えたい」）はここに入れない。印がある回は**生成そのものを行わない**ので、
 * 種別の決定より手前で分岐する。
 */
internal fun decideReunionKind(candidates: ReunionCandidates): ReunionKind = when {
    candidates.questions.isNotEmpty() -> ReunionKind.Question
    candidates.stalenessMarks.isNotEmpty() -> ReunionKind.Staleness
    else -> ReunionKind.Overview
}

/** [decideReunionKind] が選んだ種別に対応する候補列。 */
internal fun ReunionCandidates.forKind(kind: ReunionKind): List<String> = when (kind) {
    ReunionKind.Question -> questions
    ReunionKind.Staleness -> stalenessMarks
    ReunionKind.Overview -> emptyList()
}

/**
 * 古びうる記述か。**裸の西暦だけでは採らない。**
 * 「2026-08-10 に圧縮した」のような**出来事の記録**は、日付を含むが古びない。
 * 版番号と金額はそれ自体が現在の前提を述べるので単独で採り、
 * 西暦は「現在」「最新」等の**現状を述べる語**と同居したときだけ採る。
 */
private fun isStalenessCandidate(sentence: String): Boolean =
    STALENESS_MARK.containsMatchIn(sentence) ||
        (YEAR.containsMatchIn(sentence) && DATED_STATE.containsMatchIn(sentence))

/** 原文全体から、種別ごとの候補文を拾う。 */
internal fun scanReunionCandidates(content: String): ReunionCandidates {
    val questions = mutableListOf<String>()
    val staleness = mutableListOf<String>()

    for (sentence in readableSentences(content)) {
        if (questions.size >= REUNION_CANDIDATES_PER_KIND &&
            staleness.size >= REUNION_CANDIDATES_PER_KIND
        ) {
            break
        }
        // **疑問文を先に判定して排他にする。** 「2026年はどうなるか？」は両方に当たるが、
        // 同じ文が2つの種別に並ぶと、AIへ渡す候補が重複して見える。
        when {
            QUESTION_END.containsMatchIn(sentence) ->
                if (questions.size < REUNION_CANDIDATES_PER_KIND) questions += sentence
            isStalenessCandidate(sentence) ->
                if (staleness.size < REUNION_CANDIDATES_PER_KIND) staleness += sentence
        }
    }
    return ReunionCandidates(questions, staleness)
}

/**
 * 本文を「人が読む文」の列にする。
 *
 * **記法とコードを落としてから文へ割る。** コードブロック内の `?` や版番号は
 * 書き手の問いでも前提でもないので、拾うと候補がノイズで埋まる。
 */
private fun readableSentences(content: String): Sequence<String> =
    withoutFencedCode(stripFrontmatter(content))
        .lineSequence()
        .map { it.stripMarkdownMarkers() }
        .filter { it.isNotBlank() }
        .flatMap { line -> splitIntoSentences(line).asSequence() }
        .map { it.trim() }
        .filter { it.length in MIN_CANDIDATE_CHARS..MAX_CANDIDATE_CHARS }
        // **終止符で終わる断片だけを文とみなす。** これが無いと、見出し・表のセル・
        // 「最終検証: 2026-08-12」のようなラベル行が候補の大半を占める（実測で73%）。
        // ラベルは古びる前提でも書き手の問いでもないので、文の形をしていることを要求する。
        .filter { SENTENCE_END.containsMatchIn(it) }

/** 文の終わり。ラベルや見出しを候補から外すために要求する。 */
private val SENTENCE_END = Regex("""[。！？!?]\s*$""")

/**
 * 疑問文の見つけ方。**「？」だけを見ない。**
 *
 * 日本語は疑問符を伴わずに終助詞「か」で問うことが多く、実測では
 * 「？で終わる文」だけだと候補が2%しか出なかった（→ features/reunion_card.md §5）。
 *
 * **`だろうか` `のか` を並べても意味が無い** — 末尾は結局「か。」なので `か` の枝が先に食う。
 * 別に要るのは `かな` だけ（`か` の直後が `な` で、句点が来ないため）。
 *
 * 未解決かどうかの判断はここでしない。**形が問いであることまでを規則で見て、選別はAIへ渡す。**
 */
private val QUESTION_END = Regex("""(?:[?？]|(?:か|かな)[。．.])\s*$""")

/** 古びうる印。西暦・版番号風の並び・通貨記号。 */
private val STALENESS_MARK = Regex(
    // **小数を版番号と読まない。** `\d+\.\d+` は 4.5:1 や r=7.5 のような
    // 単なる計測値にも当たり、実測ではそれが候補の大半を占めた。
    // 版と読むのは `v` 接頭辞・3節以上・プレリリース語のいずれかを伴うときだけ。
    """(?:\bv\d+(?:\.\d+)+\b|\b\d+\.\d+\.\d+\b|\b(?:alpha|beta|rc)\d+\b|[¥$€£]\s?\d)""",
    RegexOption.IGNORE_CASE
)
private val DATED_STATE = Regex("""(?:現在|いまは|今は|最新|時点で|いまのところ|当面)""")
private val YEAR = Regex("""\b(?:19|20)\d{2}\b""")
