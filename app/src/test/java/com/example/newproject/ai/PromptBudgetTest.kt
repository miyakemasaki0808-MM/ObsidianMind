package com.example.newproject.ai

import com.example.newproject.domain.RelatedNotesUseCase
import com.example.newproject.model.NoteExcerpt
import com.example.newproject.model.NoteExcerptLimits
import com.example.newproject.model.PromptLimits
import com.example.newproject.model.ReadingVisit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **完成プロンプトが入力上限で閉じている**ことを固定する。
 *
 * ## なぜ要るか
 *
 * 用途別の本文上限（[NoteExcerptLimits]）はあったが、**完成プロンプト全体を閉じる制約が無かった。**
 * セクションチャットは会話履歴を全件そのまま渡し、関連候補はタイトルだけで
 * 文字数予算を超えても収まりを確かめ直さないまま返っていた。
 * 抜粋を絞っても、履歴・質問・候補名が伸びれば入力は伸びる。
 *
 * ## 見ているもの
 *
 * 1. **どんな入力でも** [PromptLimits.MAX_PROMPT_CHARACTERS] を超えないこと。
 * 2. 削られるのが材料だけで、**指示文と締め（質問・返事）は残る**こと。
 * 3. **設計が意図する最大構成では切り詰めが起きない**こと。
 *    上限を「新しい制約」にしないための歯止めで、部分予算を上げたらここが落ちる。
 *
 * ## 見ていないもの
 *
 * **実トークンでの余裕は見ていない。** 文字数とトークン数の関係は端末とモデル世代に
 * 依存するので、素のJVMでは測れない（androidTest の `PromptTokenBudgetTest` が測る）。
 */
class PromptBudgetTest {

    @Test
    fun `どの入力でも完成プロンプトが上限を超えない`() {
        PromptSamples.all(HUGE_VALUE, entries = HUGE_ENTRIES).forEach { (name, prompt) ->
            assertTrue(
                "$name が上限を超えている: ${prompt.length} > ${PromptLimits.MAX_PROMPT_CHARACTERS}",
                prompt.length <= PromptLimits.MAX_PROMPT_CHARACTERS
            )
        }
    }

    @Test
    fun `削られても質問と返事は残る`() {
        val chat = PromptBuilder.buildSectionChatPrompt(
            sectionTitle = HUGE_VALUE,
            sectionExcerpt = NoteExcerpt(HUGE_VALUE, isAbridged = true),
            history = List(HUGE_ENTRIES) { "User" to HUGE_VALUE },
            question = QUESTION
        )
        assertTrue("質問が削られている", chat.endsWith("New question:\n$QUESTION"))

        val mirror = PromptBuilder.buildRemarkMirrorPrompt(
            title = HUGE_VALUE,
            excerpt = NoteExcerpt(HUGE_VALUE, isAbridged = true),
            remark = HUGE_VALUE,
            reply = REPLY
        )
        assertTrue("返事が削られている", mirror.endsWith("Their answer:\n$REPLY"))
    }

    @Test
    fun `会話履歴は古い側から落ちる`() {
        val turns = (1..40).map { "User" to "質問$it".repeat(20) }
        val prompt = PromptBuilder.buildSectionChatPrompt(
            sectionTitle = "節",
            sectionExcerpt = NoteExcerpt("本文", isAbridged = false),
            history = turns,
            question = QUESTION
        )
        val history = prompt.substringAfter("Conversation so far:\n").substringBefore("\n\nNew question:")

        assertTrue("直近の発言が落ちている", history.contains("質問40"))
        assertFalse("古い発言が残っている", history.contains("質問1質問1"))
        assertTrue(
            "履歴の取り分を超えている: ${history.length}",
            history.length <= PromptLimits.SECTION_CHAT_HISTORY_CHARACTERS
        )
    }

    /** 履歴が空のときの文言は据え置き（本文の言語に関わらず読める形）。 */
    @Test
    fun `履歴が無いときは（なし）と書く`() {
        val prompt = PromptBuilder.buildSectionChatPrompt(
            sectionTitle = "節",
            sectionExcerpt = NoteExcerpt("本文", isAbridged = false),
            history = emptyList(),
            question = QUESTION
        )
        assertTrue(prompt.contains("Conversation so far:\n（なし / none）"))
    }

    /**
     * **上限を「新しい制約」にしない。** 現行設計が意図する最大構成（関連ノート＝
     * 抜粋 [NoteExcerptLimits.RELATED] ＋候補ブロック `RELATED_CANDIDATES_BUDGET`）が
     * 切り詰めに触れないことを確かめる。
     *
     * 部分予算を引き上げたらここが落ちるので、**[PromptLimits.MAX_PROMPT_CHARACTERS] も
     * 一緒に決め直すことになる**（片方だけ動かして黙って入力が削られるのを防ぐ）。
     */
    @Test
    fun `設計が意図する最大構成では切り詰めが起きない`() {
        val excerpt = NoteExcerpt("あ".repeat(NoteExcerptLimits.RELATED), isAbridged = true)
        val candidates = mutableListOf<RelatedCandidateLine>()
        var used = 0
        var index = 0
        while (true) {
            val line = RelatedCandidateLine("C%02d".format(index + 1), "候補".repeat(10), "文脈".repeat(60))
            val cost = line.renderForPrompt().length + if (candidates.isEmpty()) 0 else 1
            if (used + cost > RelatedNotesUseCase.RELATED_CANDIDATES_BUDGET) break
            candidates += line
            used += cost
            index++
        }

        val prompt = PromptBuilder.buildRelatedNotesPrompt(
            currentTitle = "題".repeat(PromptLimits.LABEL_CHARACTERS),
            currentExcerpt = excerpt,
            candidates = candidates
        )

        assertFalse(
            "意図する最大構成で切り詰めが起きている（${prompt.length}字）。" +
                "部分予算と ${PromptLimits.MAX_PROMPT_CHARACTERS} を一緒に見直すこと。",
            prompt.contains(PromptBudget.TRUNCATION_MARKER)
        )
        assertTrue(prompt.length <= PromptLimits.MAX_PROMPT_CHARACTERS)
    }

    /**
     * **有効な入力を共通クランプに切らせない。**
     *
     * セクション名は保存契約で512バイトまで許されるので、10件そろうと完成長が上限を超え、
     * **最後（＝最新）の訪問の行が途中で切れて到達率が消えていた。**
     * 「どこで止まりがちか」を答えるプロンプトで、最新の到達率を落とすのは意味の逆転になる。
     */
    @Test
    fun `読書痕跡は保存上限いっぱいの見出しでも最新行を壊さない`() {
        val visits = List(10) { index ->
            ReadingVisit(
                atEpochMillis = 1_770_000_000_000L + index,
                // 保存契約の上限（512バイト）いっぱいのASCII見出し。
                deepestSectionTitle = "S".repeat(512),
                progressPercent = index * 10
            )
        }

        val prompt = PromptBuilder.buildReadingTraceSummaryPrompt("題名", visits, totalVisitCount = 42)

        assertTrue("上限を超えている: ${prompt.length}", prompt.length <= PromptLimits.MAX_PROMPT_CHARACTERS)
        assertFalse("履歴が共通クランプに切られている", prompt.contains(PromptBudget.TRUNCATION_MARKER))
        assertTrue(
            "最新の訪問が完全な1行として残っていない: [${prompt.lines().last()}]",
            prompt.lines().last().matches(NEWEST_VISIT_LINE)
        )
    }

    /** 予算を超えるときに落ちるのは**古い側**。直近の読み方が消えては要約にならない。 */
    @Test
    fun `読書痕跡の履歴は古い側から落ちる`() {
        val visits = List(5) { index ->
            ReadingVisit(
                atEpochMillis = 1_770_000_000_000L + index,
                deepestSectionTitle = "節$index",
                progressPercent = index
            )
        }

        val prompt = PromptBuilder.buildReadingTraceSummaryPrompt(
            noteTitle = "題名",
            visits = visits,
            totalVisitCount = 5,
            historyCharacterBudget = 120
        )

        assertTrue("直近の訪問が落ちている", prompt.contains("節4"))
        assertFalse("古い訪問が残っている", prompt.contains("節0"))
    }

    /** 切り詰めたら黙らず印を残す。印が無いと、途中で切れた文と区別できない。 */
    @Test
    fun `切り詰めたら印を残す`() {
        val prompt = PromptBuilder.buildSummarizePrompt(
            "題名",
            NoteExcerpt(HUGE_VALUE, isAbridged = false)
        )
        assertEquals(PromptLimits.MAX_PROMPT_CHARACTERS, prompt.length)
        assertTrue(prompt.endsWith(PromptBudget.TRUNCATION_MARKER))
    }

    private companion object {
        val HUGE_VALUE = "${PromptSamples.MARK}長い値".repeat(4_000)
        const val HUGE_ENTRIES = 200
        const val QUESTION = "このセクションの結論は何ですか？"
        const val REPLY = "自分の言葉で書いた返事。"

        /** 最新の訪問は、閉じ引用符と到達率まで揃った1行で終わること。 */
        val NEWEST_VISIT_LINE = Regex("""- stopped at section ".+" \(90% of the note\)""")
    }
}
