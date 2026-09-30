package com.example.newproject.ai

import com.example.newproject.domain.RelatedNotesUseCase
import com.example.newproject.model.NoteExcerpt
import com.example.newproject.model.NoteExcerptLimits
import com.example.newproject.model.CrystalLimits
import com.example.newproject.model.CrystalMaterial
import com.example.newproject.model.PromptLimits
import com.example.newproject.model.ReunionPassage
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
     * 前後の要約は、切り出しの上限いっぱいの前後と、上限いっぱいのノート名でも切り詰められない。
     * 切り詰めは材料の末尾（＝この先の側）から削るので、起きると「この先にあること」が黙って欠ける。
     */
    @Test
    fun `前後の要約は切り出しの上限いっぱいでも切り詰めが起きない`() {
        val half = NoteExcerptLimits.REUNION_PASSAGE / 2
        val prompt = PromptBuilder.buildReunionPassagePrompt(
            noteTitle = "題".repeat(PromptLimits.LABEL_CHARACTERS),
            passage = ReunionPassage(before = "前".repeat(half), after = "後".repeat(NoteExcerptLimits.REUNION_PASSAGE - half))
        )

        assertFalse("前後の要約が切り詰められている（${prompt.length}字）", prompt.contains(PromptBudget.TRUNCATION_MARKER))
        assertTrue(prompt.length <= PromptLimits.MAX_PROMPT_CHARACTERS)
    }

    /**
     * 結晶は、候補6件・ノート名200字・断片240字の最大構成でも候補を落とさず、切り詰めも起きない。
     * 落ちると**判定した集合と送った集合がずれる**（→ features/reflect_crystal.md 判断5）。
     */
    @Test
    fun `結晶は最大構成でも候補を落とさず切り詰めが起きない`() {
        val candidates = List(CrystalLimits.CANDIDATES) { index ->
            CrystalMaterial(
                vaultRelativePath = "n$index.md",
                noteTitle = "題".repeat(PromptLimits.LABEL_CHARACTERS),
                fragment = "断".repeat(CrystalLimits.FRAGMENT_CHARACTERS),
                lastSeenAt = 0
            )
        }
        val prompt = PromptBuilder.buildCrystalPrompt(candidates)

        assertEquals(CrystalLimits.CANDIDATES, prompt.candidates.size)
        assertFalse("結晶が切り詰められている（${prompt.text.length}字）", prompt.text.contains(PromptBudget.TRUNCATION_MARKER))
        assertTrue(prompt.text.length <= PromptLimits.MAX_PROMPT_CHARACTERS)
    }

    /** 予算を超えたら古い候補から落とし、今のノート（N1）は落とさない。 */
    @Test
    fun `結晶の候補は古い側から落ち、今のノートは残る`() {
        val candidates = List(4) { CrystalMaterial("n$it.md", "題$it", "断片$it", lastSeenAt = 0) }
        val oneLine = PromptBuilder.buildCrystalPrompt(candidates, candidateBudget = 1)
        assertEquals(listOf("N1"), oneLine.candidates.map { it.id })
        val threeLines = PromptBuilder.buildCrystalPrompt(candidates, candidateBudget = 41)
        assertEquals(listOf("n0.md", "n1.md", "n2.md"), threeLines.sentMaterials.map { it.vaultRelativePath })
        assertEquals(setOf("N1", "N2", "N3"), threeLines.validIds)
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
    }
}
