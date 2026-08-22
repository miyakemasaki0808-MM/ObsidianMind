package com.example.newproject.ai

import com.example.newproject.domain.buildDistillSourceModel
import com.example.newproject.domain.selectDistillCandidates
import com.example.newproject.model.NoteExcerpt
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.ReunionKind
import com.example.newproject.model.state.QuizFormat
import java.io.File

/**
 * 本番の全プロンプトを**同じ材料で**組み立てるテスト用の口。
 *
 * 字下げ（`PromptIndentationTest`）と入力上限（`PromptBudgetTest`）はどちらも
 * 「全 builder を敵対的な値で組み立てて性質を見る」検査なので、**列挙を1本にする。**
 * 2箇所に列挙があると、builder が増えたときに片方だけへ足されて面が空く。
 *
 * [declaredBuilders] がソースを走査するので、**組み立て漏れは検査側が落とす。**
 */
internal object PromptSamples {

    /** 可変値の目印。**静的な行の判定に使う**ので、他の文言と衝突しない字を選ぶ。 */
    const val MARK = "◇"

    /**
     * 全 builder を1つの可変値 [value] で組み立てる。
     * [entries] は候補・訪問・会話履歴の件数。
     *
     * **戻り値の名前は builder 名と一致させる**（[declaredBuilders] と突き合わせるため）。
     * **2つの組み立ての間で変える入力には必ず [MARK] を含める。**
     */
    fun all(value: String, entries: Int = value.lines().size): List<Pair<String, String>> {
        val excerpt = NoteExcerpt(value, isAbridged = true)

        return listOf(
            "buildSummarizePrompt" to
                PromptBuilder.buildSummarizePrompt(value, excerpt),

            "buildReadingTraceSummaryPrompt" to
                PromptBuilder.buildReadingTraceSummaryPrompt(
                    noteTitle = value,
                    visits = List(entries) { index ->
                        ReadingVisit(
                            atEpochMillis = 1_770_000_000_000L + index,
                            deepestSectionTitle = "$MARK$index",
                            progressPercent = 40
                        )
                    },
                    totalVisitCount = 3
                ),

            "buildRelatedNotesPrompt" to
                PromptBuilder.buildRelatedNotesPrompt(
                    currentTitle = value,
                    currentExcerpt = excerpt,
                    candidates = List(entries) { RelatedCandidateLine("C0$it", "$MARK$it", "$MARK$it") }
                ),

            "buildDistillPrompt" to
                PromptBuilder.buildDistillPrompt(
                    title = value,
                    // 候補は組み立てをまたいで同一にする（＝静的な行として突き合わせられる）。
                    candidates = selectDistillCandidates(buildDistillSourceModel(DISTILL_BODY), "結論")
                ).text,

            "buildPickerPrompt" to
                PromptBuilder.buildPickerPrompt(value, List(entries) { "$MARK$it" }).text,

            "buildQuizPrompt" to
                PromptBuilder.buildQuizPrompt(value, excerpt, QuizFormat.ThreeChoice),

            "buildRemarkPrompt" to
                PromptBuilder.buildRemarkPrompt(
                    title = value,
                    excerpt = excerpt,
                    candidates = List(entries) { RemarkCandidateLine("C0$it", "$MARK$it", "$MARK$it") }
                ),

            "buildRemarkMirrorPrompt" to
                PromptBuilder.buildRemarkMirrorPrompt(
                    title = value,
                    excerpt = excerpt,
                    remark = value,
                    reply = value
                ),

            "buildSectionSummaryPrompt" to
                PromptBuilder.buildSectionSummaryPrompt(value, excerpt),

            "buildSectionSuggestionsPrompt" to
                PromptBuilder.buildSectionSuggestionsPrompt(value, excerpt),

            "buildReunionSelectionPrompt" to
                PromptBuilder.buildReunionSelectionPrompt(
                    noteTitle = value,
                    kind = ReunionKind.Question,
                    candidates = List(entries) { ReunionCandidateLine("R0$it", "$MARK$it") }
                ).text,

            "buildSectionChatPrompt" to
                PromptBuilder.buildSectionChatPrompt(
                    sectionTitle = value,
                    sectionExcerpt = excerpt,
                    history = List(entries) { "User" to "$MARK$it" },
                    question = value
                )
        )
    }

    /** `PromptBuilder` が実際に持つ builder 名。**増えたら [all] へ足すまで検査が落ちる。** */
    fun declaredBuilders(): Set<String> =
        BUILDER_DECLARATION.findAll(source()).map { it.groupValues[1] }.toSet()

    /** 実行位置が `app/` でもリポジトリ直下でも読めるようにする（`PromptGenerationCoverageTest` と同じ流儀）。 */
    private fun source(): String {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        val root = listOf(workingDirectory.resolve("src"), workingDirectory.resolve("app/src"))
            .firstOrNull { it.isDirectory }
            ?: error("app/src が見つかりません（作業ディレクトリ: $workingDirectory）")
        return root.resolve(PROMPT_BUILDER).readText()
    }

    private const val PROMPT_BUILDER = "main/java/com/example/newproject/ai/PromptBuilder.kt"

    private val DISTILL_BODY = (1..6).joinToString("\n") { "これは十分な長さを持つ重要な結論${it}の文です。" }

    private val BUILDER_DECLARATION = Regex("""\bfun\s+(build[A-Za-z]*Prompt)\s*\(""")
}
