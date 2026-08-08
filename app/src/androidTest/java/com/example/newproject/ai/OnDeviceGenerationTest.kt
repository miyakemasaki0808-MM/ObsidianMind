package com.example.newproject.ai

import android.util.Log
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.MainActivity
import com.example.newproject.domain.buildNoteExcerpt
import com.example.newproject.domain.parseQuizResponse
import com.example.newproject.model.NoteExcerptLimits
import com.example.newproject.model.state.QuizFormat
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **本番のプロンプトで実際に生成させる**（→ instrumentation_testing 段階4a）。
 *
 * ## なぜ要るか
 *
 * [PromptTokenBudgetTest] は計測（`countTokens` / `getTokenLimit`）しかしておらず、
 * **`generate()` を本番のプロンプトで通す経路が1つも無かった。**
 * `maxOutputTokens` の「1〜256」制限で**全AI生成が落ちた**前例は、まさにここが
 * 空いていたために本番まで抜けた（要約のエラーとして発覚した）。
 * 計測が緑でも生成が死ぬことはある — **上限に収まることと、生成が返ることは別**。
 *
 * ## 何を主張し、何を主張しないか
 *
 * **主張する:** 本番の各プロンプトで `generate()` が空でない応答を返すこと。
 * SDK制約・プロンプト長・API互換の破壊はここで落ちる。
 *
 * **主張しない:** 応答の中身が期待どおりの形式であること。
 * 端末AIの出力は非決定的なので、書式の一致を assert すると**壊れていないのに赤くなる**。
 * パーサへ通した結果は logcat（[TAG]）へ出し、**人間が読む材料として残す**に留める。
 * パーサについて assert するのは「実出力を渡しても例外を投げない」ことだけ。
 *
 * **この線引きを動かすなら先に考えること:** 書式一致を assert したくなったら、
 * それは本番側にフォールバック（パース失敗時の見せ方）が足りていないサインかもしれない。
 * テストを厳しくする前に、失敗したときのUXを確かめる方が効く。
 *
 * ## Activity が要る
 *
 * AICore はバックグラウンドからの利用を拒否する（`ErrorCode 30`）。
 * [PromptTokenBudgetTest] と同じ理由で [ActivityScenarioRule] を置く。
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceGenerationTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val client = AICoreClient()

    /**
     * 端末AIが使えるときだけ生成する。
     *
     * `checkAvailability()` は例外まで `Unavailable` へ畳むので判定に使わない
     * （SDKの回帰が「非対応端末」に化けて見逃される）。生の [FeatureStatus] だけで判断し、
     * **生成呼び出し自体が投げた例外は skip せずそのまま失敗させる。**
     */
    private fun requireNanoAvailable() {
        val status = runBlocking { client.featureStatus() }
        assumeTrue(
            "端末AIが利用可能ではないため生成を飛ばす（FeatureStatus=$status）。",
            status == FeatureStatus.AVAILABLE
        )
    }

    @Test
    fun 要約プロンプトで生成が返る() = runBlocking {
        requireNanoAvailable()

        val prompt = PromptBuilder.buildSummarizePrompt(
            title = NOTE_TITLE,
            excerpt = buildNoteExcerpt(NOTE_BODY, NoteExcerptLimits.SUMMARY)
        )
        val response = client.generate(prompt)

        log("要約", response)
        assertGenerated("要約", response)
    }

    /**
     * クイズは**構造化された出力**を本番パーサへ渡す唯一の経路。
     *
     * 書式が一致するかは assert しない（非決定的）。パース結果は logcat へ出す。
     */
    @Test
    fun クイズプロンプトで生成が返りパーサが実出力を処理できる() = runBlocking {
        requireNanoAvailable()

        val prompt = PromptBuilder.buildQuizPrompt(
            sourceLabel = NOTE_TITLE,
            excerpt = buildNoteExcerpt(NOTE_BODY, NoteExcerptLimits.QUIZ),
            format = QuizFormat.TrueFalse
        )
        val response = client.generate(prompt)
        log("クイズ", response)
        assertGenerated("クイズ", response)

        // 例外を投げないことだけを assert する。件数は観測値として残す。
        val cards = parseQuizResponse(response, QuizFormat.TrueFalse)
        Log.i(TAG, "クイズのパース結果: ${cards.size}件" + if (cards.isEmpty()) "（書式不一致）" else "")
    }

    /** 関連ノートは候補IDだけを返させる経路。プロンプトの指示が強く、長さも他と違う。 */
    @Test
    fun 関連ノートプロンプトで生成が返る() = runBlocking {
        requireNanoAvailable()

        val prompt = PromptBuilder.buildRelatedNotesPrompt(
            currentTitle = NOTE_TITLE,
            currentExcerpt = buildNoteExcerpt(NOTE_BODY, NoteExcerptLimits.RELATED),
            candidates = listOf(
                RelatedCandidateLine(id = "C01", title = "習慣の作り方"),
                RelatedCandidateLine(id = "C02", title = "読書メモの残し方", detail = "再読のための記録"),
                RelatedCandidateLine(id = "C03", title = "献立の記録")
            )
        )
        val response = client.generate(prompt)

        log("関連ノート", response)
        assertGenerated("関連ノート", response)
    }

    /** セクションチャットは会話文脈を含む経路。他の3つと入力の作り方が違う。 */
    @Test
    fun セクション要約プロンプトで生成が返る() = runBlocking {
        requireNanoAvailable()

        val prompt = PromptBuilder.buildSectionSummaryPrompt(
            sectionTitle = "習慣について",
            sectionExcerpt = buildNoteExcerpt(NOTE_BODY, NoteExcerptLimits.SECTION)
        )
        val response = client.generate(prompt)

        log("セクション要約", response)
        assertGenerated("セクション要約", response)
    }

    // --- 補助 -----------------------------------------------------------------

    private fun assertGenerated(label: String, response: String) {
        assertTrue(
            "$label の生成が空だった。SDK制約・プロンプト長・API互換のいずれかを疑う。",
            response.isNotBlank()
        )
    }

    private fun log(label: String, response: String) {
        Log.i(TAG, "── $label ${"─".repeat(30)}")
        Log.i(TAG, "応答長: ${response.length}文字")
        response.lineSequence().take(12).forEach { Log.i(TAG, "  $it") }
    }

    private companion object {
        const val TAG = "OnDeviceGeneration"

        const val NOTE_TITLE = "習慣について"

        /** 生成の材料。**短すぎると空応答になりやすい**ので、意味のある長さを持たせる。 */
        val NOTE_BODY = """
            # 習慣について

            習慣は意志の力ではなく仕組みで作る。やる気に頼ると、疲れている日に途切れる。

            ## きっかけを固定する

            行動の前に必ず起きることへ結び付けると、思い出す必要がなくなる。
            朝のコーヒーを淹れる間に読む、というように既存の行動へ寄生させるのがよい。

            ## 小さく始める

            最初から完璧な量を目指すと、達成できない日が生まれて自己評価が下がる。
            1日1ページでも続いている状態のほうが、週に一度の大量読書より効く。

            ## 記録を残す

            続いていることが見えると、途切れさせたくないという力が働く。
            記録は評価のためではなく、継続そのものを支える道具として使う。
        """.trimIndent()
    }
}
