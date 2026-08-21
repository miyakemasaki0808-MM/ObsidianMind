package com.example.newproject.ai

import com.example.newproject.domain.buildDistillSourceModel
import com.example.newproject.domain.selectDistillCandidates
import com.example.newproject.model.NoteExcerpt
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.state.QuizFormat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **複数行の値を埋めても、テンプレート側の字下げがプロンプトへ漏れない**ことを固定する。
 *
 * ## なぜ要るか
 *
 * `trimIndent()` は**補間後の文字列**に効く。複数行の値を埋めるとその2行目以降が
 * インデント0の行として混ざり、共通インデントが0と判定されて**テンプレート側の
 * 12スペースが全行に残る**。本文抜粋・候補一覧・会話履歴はいずれも複数行になり得るので、
 * 実運用ではほぼ常に発生していた。
 *
 * **既存の回帰テストは値を単一行で比較していたので、緑のまま通り抜けた。**
 * ここでは全 builder を**複数行の値で**組み立てて判定する。
 *
 * ## 見ているもの
 *
 * 1. プロンプトが空白で始まらないこと。
 *    テンプレート先頭は必ず指示文なので、`trimIndent()` が効かなかった瞬間にここが崩れる。
 *    **共通インデントは全行から算出されるため、値がどこに埋まっていても先頭行が巻き込まれる** —
 *    つまりこの1点でこの不具合の全発生形を捕まえられる。
 * 2. 単一行の値で組んだときの**静的な行**が、複数行の値でもそのまま現れること。
 *    先頭行だけでは、テンプレート中ほどの字下げ崩れを見落とし得る。
 *
 * ## 約束事
 *
 * **2つの組み立ての間で変える入力には必ず [MARK] を含める。** 静的な行は
 * 「[MARK] を含まない行」として数えるので、印の無い可変値を混ぜると
 * 静的な行の判定がずれる。
 */
class PromptIndentationTest {

    @Test
    fun `全プロンプトが複数行の値でも字下げを持ち込まない`() {
        cases().forEach { (name, prompt) ->
            assertTrue(
                "$name: プロンプトが空白で始まっている（trimIndent が効いていない）。\n" +
                    prompt.lines().take(3).joinToString("\n") { "[$it]" },
                prompt.first() != ' '
            )
            prompt.lines().forEach { line ->
                assertTrue(
                    "$name: 余分な先頭空白を持つ行がある: [$line]",
                    !line.startsWith(TEMPLATE_INDENT)
                )
            }
        }
    }

    @Test
    fun `静的な行は値が複数行になっても変わらない`() {
        val single = cases(SINGLE_LINE_VALUE).toMap()
        val multi = cases(MULTI_LINE_VALUE).toMap()

        single.forEach { (name, expected) ->
            val actualLines = requireNotNull(multi[name]) { "$name の組み立てが片方にしかない" }.lines()
            expected.lines().filterNot { it.contains(MARK) }.forEach { staticLine ->
                assertTrue(
                    "$name: 値を複数行にしたら静的な行が変わった: [$staticLine]",
                    actualLines.contains(staticLine)
                )
            }
        }
    }

    /** builder が増えたらここへ足すまで落ちる（分類漏れを防ぐ → `PromptGenerationCoverageTest` と同じ形）。 */
    @Test
    fun `本番の全 builder を組み立てている`() {
        val declared = BUILDER_DECLARATION
            .findAll(promptBuilderSource())
            .map { it.groupValues[1] }
            .toSortedSet()

        assertEquals(
            "字下げ検査から漏れている builder があります。cases() へ足してください。",
            declared,
            cases().map { it.first }.toSortedSet()
        )
    }

    // --- 組み立て -------------------------------------------------------------

    /**
     * 全 builder を1つの可変値 [value] で組み立てる。**戻り値の名前は builder 名と一致させる**
     * （`本番の全 builder を組み立てている` が名前で突き合わせるため）。
     */
    private fun cases(value: String = MULTI_LINE_VALUE): List<Pair<String, String>> {
        val excerpt = NoteExcerpt(value, isAbridged = true)
        // 単一行・複数行のどちらでも「静的な行」の集合が変わらないよう、件数も1件と2件で振る。
        val entries = value.lines().size

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
                    // 候補は両方の組み立てで同一にする（＝静的な行として突き合わせられる）。
                    candidates = selectDistillCandidates(buildDistillSourceModel(DISTILL_BODY), "結論")
                ).text,

            "buildPickerPrompt" to
                PromptBuilder.buildPickerPrompt(value, List(entries) { "$MARK$it" }),

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

            "buildSectionChatPrompt" to
                PromptBuilder.buildSectionChatPrompt(
                    sectionTitle = value,
                    sectionExcerpt = excerpt,
                    history = List(entries) { "User" to "$MARK$it" },
                    question = value
                )
        )
    }

    /** 実行位置が `app/` でもリポジトリ直下でも読めるようにする（`PromptGenerationCoverageTest` と同じ流儀）。 */
    private fun promptBuilderSource(): String {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        val roots = listOf(workingDirectory.resolve("src"), workingDirectory.resolve("app/src"))
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("app/src が見つかりません（作業ディレクトリ: $workingDirectory）")
        return root.resolve(PROMPT_BUILDER).readText()
    }

    private companion object {
        /** 可変値の目印。**静的な行の判定に使う**ので、他の文言と衝突しない字を選ぶ。 */
        const val MARK = "◇"
        const val SINGLE_LINE_VALUE = "${MARK}1"
        const val MULTI_LINE_VALUE = "${MARK}1\n${MARK}2"

        /** テンプレート側の字下げ（raw string のインデント）。これが漏れていたら不具合。 */
        const val TEMPLATE_INDENT = "    "

        val DISTILL_BODY = (1..6).joinToString("\n") { "これは十分な長さを持つ重要な結論${it}の文です。" }

        const val PROMPT_BUILDER = "main/java/com/example/newproject/ai/PromptBuilder.kt"

        val BUILDER_DECLARATION = Regex("""\bfun\s+(build[A-Za-z]*Prompt)\s*\(""")
    }
}
