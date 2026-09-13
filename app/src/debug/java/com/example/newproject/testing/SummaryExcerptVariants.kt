package com.example.newproject.testing

import com.example.newproject.ai.PromptBuilder
import com.example.newproject.domain.buildNoteExcerpt
import com.example.newproject.model.NoteExcerpt
import com.example.newproject.model.NoteExcerptLimits

// 要約の抜粋の作り方を並べて測るための変種（→ docs/dev/system/ai_quality_measurement.md）。
//
// **`debug` に置く理由は `SummaryCoverage` と同じ。** 本番経路から呼ばないものを製品へ載せない。
// 変種が作るのは `NoteExcerpt` までで、プロンプトの組み立ては本番の [PromptBuilder] を通す
// — そこを真似すると、測っているのが本番と別物になる。

/** 抜粋の作り方ひとつ。 */
class SummaryExcerptVariant(
    /**
     * 照合に使う名前。**ASCIIに限る** — 実機の引数（`-e variants`）・logcat の `RESULT` 行・
     * 取り込んだ出力のファイル名（`references/nano/<note>.<key>.txt`）で同じ綴りを使う。
     */
    val key: String,
    /** 人が読む名前。表の見出しにだけ使う。 */
    val name: String,
    /** その変種が本文に与える文字数の上限。表を読むとき、予算違いを見分けるために持つ。 */
    val budget: Int,
    val buildExcerpt: (content: String) -> NoteExcerpt
) {
    fun buildPrompt(title: String, content: String): String =
        PromptBuilder.buildSummarizePrompt(title, buildExcerpt(content))
}

/**
 * 測る対象。**1つ目は本番そのもの**で、差し替えず基準線に使う。
 *
 * 2つ目が **2026-07-27 に置き換えた側**である。「後ろ捨て（先頭固定長）」から
 * 「真ん中捨て（骨格＋冒頭＋末尾）」へ変えた判断は理屈としては正しかったが、
 * **良くなったことを示す測定が無いまま**今日まで来た。ここが、その宿題を解く口である。
 *
 * 3つ目は予算そのものを問う。`SUMMARY = 1,200` は**品質の観点では一度も検証されていない**
 * （実トークンの余裕は測ってあるが、それは「収まるか」であって「良いか」ではない）。
 *
 * **ここから方式の優劣を確定しない。** 入力が変種で変わるのは長文3本だけで、生成は決定的なので
 * 繰り返しても揺らぎは測れない。1回の表は探索の手がかりであり、小さな差で予算案を捨てない
 * （→ docs/dev/system/ai_quality_measurement.md 判断6）。
 *
 * **足すぶんだけ実機の時間が伸びる。** 生成は Mutex で直列化され1件あたり最大60秒、
 * コーパス7本 × 変種の数だけ走る。
 */
val SUMMARY_EXCERPT_VARIANTS = listOf(
    SummaryExcerptVariant("production", "本番", NoteExcerptLimits.SUMMARY) { content ->
        buildNoteExcerpt(content, NoteExcerptLimits.SUMMARY)
    },
    SummaryExcerptVariant("head_only", "冒頭のみ（旧方式）", NoteExcerptLimits.SUMMARY) { content ->
        headOnlyExcerpt(content, NoteExcerptLimits.SUMMARY)
    },
    SummaryExcerptVariant("budget_x2", "本番・予算2倍", NoteExcerptLimits.SUMMARY * 2) { content ->
        buildNoteExcerpt(content, NoteExcerptLimits.SUMMARY * 2)
    }
)

/**
 * 2026-07-27 より前の切り出し方。**先頭から予算ぶん取り、残りは捨てる。**
 *
 * 注意書き（`ABRIDGED_NOTICE_PREFIX`）は本番と同じように付ける。
 * **付けないと、比べているのが切り出し方なのか注意書きの有無なのか分からなくなる。**
 */
internal fun headOnlyExcerpt(content: String, budget: Int): NoteExcerpt {
    require(budget >= NoteExcerptLimits.ABRIDGED_NOTICE_PREFIX.length) {
        "budget must fit the abridged notice and separator"
    }
    if (content.length <= budget) return NoteExcerpt(content, isAbridged = false)

    val textBudget = budget - NoteExcerptLimits.ABRIDGED_NOTICE_PREFIX.length
    return NoteExcerpt(content.takeWithoutSplittingPair(textBudget), isAbridged = true)
}

/**
 * 代理対を割らずに先頭から取る。**絵文字や一部の漢字は2つの `Char` で1文字**なので、
 * 素の `take` は壊れた文字を末尾に残す（本番側も `safeTake` で同じことをしている）。
 */
private fun String.takeWithoutSplittingPair(count: Int): String {
    if (count >= length) return this
    val safe = if (count > 0 && this[count - 1].isHighSurrogate()) count - 1 else count
    return take(safe)
}
