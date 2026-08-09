package com.example.newproject.ai

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.MainActivity
import com.example.newproject.domain.RelatedNotesUseCase
import com.example.newproject.domain.buildDistillSourceModel
import com.example.newproject.domain.buildNoteExcerpt
import com.example.newproject.domain.selectDistillCandidates
import com.example.newproject.model.NoteExcerpt
import com.example.newproject.model.NoteExcerptLimits
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.state.QuizFormat
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 完成プロンプトが端末AIのトークン上限に対してどれだけ余裕を持っているかを実測する。
 *
 * ## なぜ実機テストなのか
 *
 * `countTokens()` / `getTokenLimit()` は AICore（Gemini Nano）へ問い合わせるため、
 * **素のJVMテストでは呼べない**。文字数上限（[NoteExcerptLimits]）は静的に決まるが、
 * それが実トークンで何を意味するかは端末とモデル世代に依存する。
 *
 * ## 何を判定しているか
 *
 * `getTokenLimit()` は**入力と出力の合計上限**で、`countTokens()` が数えるのは入力だけ。
 * したがって「まだ入るか」は入力単体では判断できず、生成のために予約される
 * `maxOutputTokens` も引いた **headroom** で見る（[PromptTokenMeasurement]）。
 * 入力だけを見て余裕があると読むと、予算を上げた結果として生成側が押し出される。
 *
 * ## このテストが保証しないこと
 *
 * **これは「定義済み計測プロファイルの回帰テスト」であって、入力長の上限保証ではない。**
 * 本番にはまだ上限の無い可変入力が複数ある — セクションチャットは会話履歴を全件そのまま渡し、
 * 関連候補はタイトルだけで文字数予算を超えた場合に収まらないまま返る。
 * 真の上限保証は本番側へ上限を入れる別作業であり、ここでは扱わない。
 * **緑であることを「どんな入力でも上限内」と読んではいけない。**
 *
 * 計測値は logcat のタグ [TAG] へ表として出す。予算値（特に関連ノートの
 * [NoteExcerptLimits.RELATED]）を動かしてよいかは、この出力を見てから判断する。
 */
@RunWith(AndroidJUnit4::class)
class PromptTokenBudgetTest {

    /**
     * **AICore はバックグラウンドからの利用を拒否する。**
     *
     * Activity を立てずに端末AIを呼ぶと `GenAiException [ErrorCode 30]
     * "Background usage is blocked. Please use the API when your app is in the foreground instead."`
     * になる。instrumentation テストは既定でフォアグラウンドのActivityを持たないため、
     * **このルールが無いと端末AIを使うテストは端末側の問題と区別できない形で必ず落ちる。**
     *
     * 実際に一度そうなった。`countTokens()` だけが `10003 Tokenization failed` という
     * 別のメッセージで落ちたため「beta2 の計測APIが Android 17 で壊れている」と読みかけたが、
     * 対照に置いた `generate()` が ErrorCode 30 で落ちたことで**推論経路全体が
     * 止められている**と分かった（`getTokenLimit()` はメタデータ照会なので通っていた）。
     * 10003 は同じ原因をトークナイザ側が別の文言で返しているものと見る。
     *
     * `ComposeRenderingSetupTest` が緑なのは Activity を立てているからで、
     * **端末AIを呼ぶテストでは Activity の有無が前提条件そのものになる。**
     */
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val client = AICoreClient()

    /**
     * 端末AIが使えるときだけ計測する。
     *
     * skip 判定に [AICoreClient.checkAvailability] を使わないのは、あれが例外まで
     * `Unavailable` へ畳むため、**SDKの回帰が「非対応端末」に化けて見逃される**から。
     * ここでは生の [FeatureStatus] だけで判断し、計測呼び出し自体が投げた例外は
     * skip せずそのまま失敗させる。
     */
    private fun requireNanoAvailable() {
        val status = runBlocking { client.featureStatus() }
        assumeTrue(
            "端末AIが利用可能ではないため計測を飛ばす（FeatureStatus=$status）。" +
                "Nano対応端末で実行すること。",
            status == FeatureStatus.AVAILABLE
        )
    }

    /**
     * どの能力が使えてどれが使えないかを、1回の実行で切り分ける。
     *
     * **端末AIの能力は「生成できる」と「トークンを数えられる」が別々に決まり得る。**
     * まとめて呼ぶと、落ちたときに欠けている能力を特定できない（実際に一度そうなった）。
     * ここでは各呼び出しを独立に試し、成否を表にしてから**まとめて**失敗させる。
     * 最初の1つで止めると、2つ目以降が使えるかどうかが分からないまま終わる。
     *
     * このテストは他の計測テストより先に読む前提の診断であり、
     * **`FeatureStatus` を確認する前に走らせる**（`checkStatus()` 自体が投げる可能性があるため）。
     */
    /**
     * トークンAPIの最小疎通。**計測ケースを疑う前に、APIそのものが動くかを見る。**
     *
     * 読み方:
     * - `tokenLimit` 成功・`"hello"` 失敗 → `countTokens()` 経路のAICore／SDK互換問題
     * - 両方失敗 → token-info 機能全体が使えない
     * - 両方成功 → 元の失敗はプロンプト固有（長さ・内容）なので段階的に切り分ける
     */
    @Test
    fun トークンAPIの最小疎通を確かめる() = runBlocking<Unit> {
        requireNanoAvailable()
        log("── 最小疎通 ${"─".repeat(37)}")
        log("getTokenLimit()          : ${client.tokenLimit()}")
        log("countTokens(\"hello\")     : ${client.countPromptTokens("hello")}")
    }

    @Test
    fun 端末AIのどの能力が使えるかを切り分ける() {
        logHeader()
        val results = listOf(
            probe("checkStatus()") { featureStatusLabel(client.featureStatus()) },
            probe("getBaseModelName()") { client.baseModelName() },
            probe("maxOutputTokens（端末へ問い合わせない）") { client.reservedOutputTokens().toString() },
            probe("countTokens()（warmup前）") { client.countPromptTokens(PROBE_PROMPT).toString() },
            probe("getTokenLimit()（warmup前）") { client.tokenLimit().toString() },
            // トークン系がセッション確立を前提にしている可能性を切り分ける。
            // warmup 後だけ通るなら、計測の前に warmup を挟めば済む。
            probe("warmup()") { client.warmup(); "ok" },
            probe("countTokens()（warmup後）") { client.countPromptTokens(PROBE_PROMPT).toString() },
            probe("getTokenLimit()（warmup後）") { client.tokenLimit().toString() },
            // 生成が通ることを同じ実行の中で確かめる。これが通ってトークン系だけ落ちるなら、
            // 「端末AIが使えない」ではなく「計測APIだけ使えない」が確定する。
            probe("generate()（対照）") { client.generate("1+1は？ 数字だけ答えて。").take(40) }
        )

        log("── 能力プローブ ${"─".repeat(34)}")
        results.forEach { log(it.render()) }

        val failed = results.filter { it.error != null }
        assertTrue(
            "端末AIの能力プローブが失敗した:\n" + failed.joinToString("\n") { it.render() },
            failed.isEmpty()
        )
    }

    private fun probe(name: String, block: suspend () -> String): ProbeResult =
        try {
            ProbeResult(name, runBlocking { block() }, null)
        } catch (e: Exception) {
            ProbeResult(name, null, "${e.javaClass.simpleName}: ${e.message}")
        }

    private data class ProbeResult(val name: String, val value: String?, val error: String?) {
        fun render(): String = "%-38s %s".format(name, value ?: "✗ $error")
    }

    private fun featureStatusLabel(status: Int): String = when (status) {
        FeatureStatus.AVAILABLE -> "AVAILABLE"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING"
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
        else -> "UNKNOWN($status)"
    }

    @Test
    fun 全計測ケースが入力と出力予約の合計を上限内に収める() = runBlocking<Unit> {
        requireNanoAvailable()
        logHeader()

        Profile.entries.forEach { profile ->
            log("── プロファイル: ${profile.label} ${"─".repeat(28)}")
            log(String.format("%-22s %8s %8s %8s %9s", "計測ケース", "入力", "出力予約", "上限", "余裕"))

            measurementCases(profile).forEach { (name, prompt) ->
                val m = client.measurePrompt(prompt)
                log(
                    String.format(
                        "%-22s %8d %8d %8d %9d",
                        name, m.inputTokens, m.maxOutputTokens, m.tokenLimit, m.headroom
                    )
                )
                assertTrue(
                    "${profile.label} / $name が上限を超えている: " +
                        "入力${m.inputTokens} + 出力予約${m.maxOutputTokens} > 上限${m.tokenLimit}",
                    m.headroom >= 0
                )
            }
        }
    }

    /**
     * 抜粋の注意書き（[NoteExcerptLimits.ABRIDGED_NOTICE_PREFIX]・226文字）が実際に何トークンかを測る。
     *
     * **注意書き単体を `countTokens` にかけても答えにならない。** 前後の文字列と結合すると
     * トークン境界が変わるため、完成プロンプトへの寄与とは一致しない。
     * そこで**同じ本文で「注意書きあり」と「なし」の差**を取る。この差が、関連ノートの
     * 予算600のうち注意書きへ払っている実コストになる。
     */
    @Test
    fun 抜粋の注意書きが占めるトークン数を差分で測る() = runBlocking<Unit> {
        requireNanoAvailable()
        logHeader()

        val body = Profile.JA_PROSE.content.take(NoteExcerptLimits.RELATED)
        val candidates = relatedCandidates(Profile.JA_PROSE)

        val withNotice = PromptBuilder.buildRelatedNotesPrompt(
            currentTitle = TITLE,
            currentExcerpt = NoteExcerpt(body, isAbridged = true),
            candidates = candidates
        )
        val withoutNotice = PromptBuilder.buildRelatedNotesPrompt(
            currentTitle = TITLE,
            currentExcerpt = NoteExcerpt(body, isAbridged = false),
            candidates = candidates
        )

        val abridged = client.measurePrompt(withNotice)
        val plain = client.measurePrompt(withoutNotice)
        val noticeTokens = abridged.inputTokens - plain.inputTokens

        log("── 注意書きの実コスト（関連ノート経路） ${"─".repeat(16)}")
        log("注意書きの文字数        : ${NoteExcerptLimits.ABRIDGED_NOTICE_PREFIX.length}")
        log("注意書きありの入力      : ${abridged.inputTokens} トークン")
        log("注意書きなしの入力      : ${plain.inputTokens} トークン")
        log("差分（注意書きの実コスト）: $noticeTokens トークン")
        log("関連ノートの余裕        : ${abridged.headroom} トークン")

        assertTrue("注意書きを足したのに入力トークンが増えていない", noticeTokens > 0)
        assertTrue("注意書きありの関連ノートが上限を超えている", abridged.headroom >= 0)
    }

    /**
     * 関連ノートの抜粋予算（[NoteExcerptLimits.RELATED]）を動かしたときの余裕を、候補値ごとに測る。
     *
     * **関連ノートの予算が実測より小さいのではないかを確かめるための掃引。** 1回の実行で候補を全部測るのは、
     * 「変えて回す」を繰り返すと実機実行が候補の数だけ要るため。
     *
     * 見るのは余裕だけではない。**100文字あたりの限界コスト**も出す。
     * 日本語は1文字あたりのトークンが英語より高く、文字数を倍にしてもトークンが倍にならない
     * （骨格・ラベル・注意書きの固定費が効く）ため、**文字数の増分から素朴に見積もると外れる。**
     *
     * このテストは判断材料を出すだけで、何も強制しない。**予算を決めるのは人間の仕事**であり、
     * トークンの余裕は必要条件にすぎない（生成時間と推薦品質は別途見る）。
     */
    @Test
    fun 関連ノートの予算候補ごとの余裕を測る() = runBlocking<Unit> {
        requireNanoAvailable()
        logHeader()

        Profile.entries.forEach { profile ->
            val candidates = relatedCandidates(profile)
            log("── 関連ノート予算の掃引: ${profile.label} ${"─".repeat(18)}")
            log(String.format("%8s %8s %8s %9s %14s", "予算(字)", "入力", "上限", "余裕", "限界費用/100字"))

            var previous: Pair<Int, Int>? = null
            RELATED_BUDGET_CANDIDATES.forEach { budget ->
                val prompt = PromptBuilder.buildRelatedNotesPrompt(
                    currentTitle = TITLE,
                    currentExcerpt = buildNoteExcerpt(profile.content, budget),
                    candidates = candidates
                )
                val m = client.measurePrompt(prompt)
                val marginal = previous?.let { (prevBudget, prevTokens) ->
                    val deltaChars = budget - prevBudget
                    if (deltaChars == 0) "—" else
                        "%.1f".format((m.inputTokens - prevTokens) * 100.0 / deltaChars)
                } ?: "—"
                log(
                    String.format(
                        "%8d %8d %8d %9d %14s",
                        budget, m.inputTokens, m.tokenLimit, m.headroom, marginal
                    )
                )
                previous = budget to m.inputTokens
            }
        }
    }

    // ── 計測ケース ────────────────────────────────────────────────────────────

    /**
     * 機能経路は8つ（要約・関連ノート・補記・クイズ・セクション・蒸留・読書痕跡要約・検索ピッカー）。
     * ここではセクションを3種、クイズを3形式へ分解して**12ケース**として測る。
     * 同じ経路でも指示文の長さが違えばトークン数が変わるため、束ねると読み違える。
     */
    private fun measurementCases(profile: Profile): List<Pair<String, String>> {
        val content = profile.content
        val summaryExcerpt = buildNoteExcerpt(content, NoteExcerptLimits.SUMMARY)
        val annotationExcerpt = buildNoteExcerpt(content, NoteExcerptLimits.ANNOTATION)
        val relatedExcerpt = buildNoteExcerpt(content, NoteExcerptLimits.RELATED)
        val sectionExcerpt = buildNoteExcerpt(content, NoteExcerptLimits.SECTION)
        val quizExcerpt = buildNoteExcerpt(content, NoteExcerptLimits.QUIZ)

        return buildList {
            add("要約" to PromptBuilder.buildSummarizePrompt(TITLE, summaryExcerpt))

            add(
                "関連ノート" to PromptBuilder.buildRelatedNotesPrompt(
                    currentTitle = TITLE,
                    currentExcerpt = relatedExcerpt,
                    candidates = relatedCandidates(profile)
                )
            )

            add(
                "補記" to PromptBuilder.buildAnnotationPrompt(
                    title = TITLE,
                    excerpt = annotationExcerpt,
                    summary = profile.content.take(200),
                    relatedTitles = List(5) { "${profile.label}の関連ノート${it + 1}" },
                    aiRecommendedTitles = List(5) { "${profile.label}のAI推薦${it + 1}" },
                    wikilinkTitles = List(10) { "${profile.label}のwikilink${it + 1}" }.toSet(),
                    createdAt = "2026-08-01 12:00"
                )
            )

            QuizFormat.entries.forEach { format ->
                add(
                    "クイズ(${format.displayName})" to
                        PromptBuilder.buildQuizPrompt(TITLE, quizExcerpt, format)
                )
            }

            add("セクション要約" to PromptBuilder.buildSectionSummaryPrompt(SECTION, sectionExcerpt))
            add("セクション提案" to PromptBuilder.buildSectionSuggestionsPrompt(SECTION, sectionExcerpt))
            add(
                "セクションチャット" to PromptBuilder.buildSectionChatPrompt(
                    sectionTitle = SECTION,
                    sectionExcerpt = sectionExcerpt,
                    // 本番は履歴を全件渡す。計測では現実的な往復数を置く。
                    history = List(CHAT_HISTORY_TURNS) { turn ->
                        (if (turn % 2 == 0) "User" else "AI") to profile.content.take(120)
                    },
                    question = "このセクションの結論は何ですか？"
                )
            )

            add(
                "蒸留" to PromptBuilder.buildDistillPrompt(
                    title = TITLE,
                    candidates = selectDistillCandidates(buildDistillSourceModel(content), TITLE)
                ).text
            )

            add(
                "読書痕跡要約" to PromptBuilder.buildReadingTraceSummaryPrompt(
                    noteTitle = TITLE,
                    visits = List(READING_TRACE_VISITS) { index ->
                        ReadingVisit(
                            atEpochMillis = 1_770_000_000_000L + index * 86_400_000L,
                            deepestSectionTitle = "$SECTION $index",
                            progressPercent = (index * 7) % 100
                        )
                    },
                    totalVisitCount = 42
                )
            )

            add(
                "検索ピッカー" to PromptBuilder.buildPickerPrompt(
                    query = "オンデバイスAIの制約について書いたノートを探して",
                    candidateTitles = List(PICKER_CANDIDATES) { "${profile.label}の候補ノート${it + 1}" }
                )
            )
        }
    }

    /** 候補ブロックを本番と同じ文字数予算いっぱいまで埋める。 */
    private fun relatedCandidates(profile: Profile): List<RelatedCandidateLine> {
        val lines = mutableListOf<RelatedCandidateLine>()
        var used = 0
        var index = 0
        while (true) {
            val line = RelatedCandidateLine(
                id = "C%02d".format(index + 1),
                title = "${profile.label}の候補ノート${index + 1}",
                detail = profile.content.take(RELATED_SNIPPET_LEN)
            )
            val cost = line.renderForPrompt().length + if (lines.isEmpty()) 0 else 1
            if (used + cost > RelatedNotesUseCase.RELATED_CANDIDATES_BUDGET) break
            lines += line
            used += cost
            index++
        }
        return lines
    }

    // ── ログ ──────────────────────────────────────────────────────────────────

    /**
     * 端末・モデル・SDK版を毎回添える。これが無いと、別端末で採った数値と並べたときに
     * 「モデルが違うのか予算が違うのか」を後から切り分けられない。
     *
     * **ヘッダの取得で失敗させない。** `getBaseModelName()` も端末AIへの問い合わせなので
     * 投げ得るが、ここで落とすと**本題の計測が始まる前にテストが終わり、何が使えないのかが
     * 分からなくなる**。取れなければ理由を書いて先へ進み、判定は各テスト本体に任せる。
     */
    private fun logHeader() {
        val modelName = try {
            runBlocking { client.baseModelName() }
        } catch (e: Exception) {
            "取得できず（${e.javaClass.simpleName}: ${e.message}）"
        }
        log("=".repeat(64))
        log("端末            : ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        log("baseModelName   : $modelName")
        log("genai-prompt    : $GENAI_PROMPT_VERSION")
        log("=".repeat(64))
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    private enum class Profile(val label: String, val content: String) {
        /** 通常運用に近い日本語の散文ノート。 */
        JA_PROSE("日本語散文", jaProseNote()),

        /** 同じ文字数でもトークンが膨らむ側。コード・URL・UUID・記号が混ざる。 */
        DENSE("高密度混在", denseNote())
    }

    private companion object {
        const val TAG = "PromptTokens"

        /** `app/build.gradle.kts` の宣言と揃える。ログの数値がどのSDKで採れたかを残すため。 */
        const val GENAI_PROMPT_VERSION = "1.0.0-beta2"

        /**
         * 関連ノート予算の候補（先頭は現行値）。
         *
         * 上限側を 2,000 まで見るのは、**採る気がなくても曲線の形を知るため**。
         * 現行値の周辺だけ測ると、限界費用が一定なのか逓減するのかが分からない。
         */
        val RELATED_BUDGET_CANDIDATES = listOf(600, 800, 1000, 1200, 1500, 2000)

        const val PROBE_PROMPT = "トークン計測の疎通確認"
        const val TITLE = "オンデバイスAIの入力予算に関する検討"
        const val SECTION = "予算配分の考え方"
        const val RELATED_SNIPPET_LEN = 150
        const val CHAT_HISTORY_TURNS = 6
        const val READING_TRACE_VISITS = 10
        const val PICKER_CANDIDATES = 40

        /** 全用途の文字数予算を確実に超える長さにする（＝抜粋と注意書きが必ず働く）。 */
        fun jaProseNote(): String = buildString {
            appendLine("---")
            appendLine("tags: [ai, budget]")
            appendLine("---")
            appendLine()
            (1..12).forEach { section ->
                appendLine("## 第${section}節 予算配分の考え方")
                appendLine()
                repeat(3) { paragraph ->
                    appendLine(
                        "オンデバイスのモデルは入力と出力の双方にトークン上限を持つため、" +
                            "本文をそのまま渡すのではなく用途ごとに予算を切って抜粋する必要がある。" +
                            "第${section}節の第${paragraph + 1}段落では、見出し骨格・冒頭・末尾へどう配分するかを検討し、" +
                            "捨てる場所を後半ではなく中盤に置く判断の理由を整理している。"
                    )
                }
                appendLine()
            }
        }

        fun denseNote(): String = buildString {
            appendLine("## Dense payload")
            appendLine()
            (1..12).forEach { index ->
                appendLine("- id=`3f2a9c1${index}-4b8e-4d7a-9f01-2c6d8e5a7b3${index % 10}`")
                appendLine("- url=https://example.invalid/vault/notes/$index?query=token&limit=4096#anchor-$index")
                appendLine()
                appendLine("```")
                appendLine("val budget_$index = mapOf(\"summary\" to 1200, \"related\" to 600)")
                appendLine("require(budget_$index.values.sum() <= 4096) { \"over: \$index\" }")
                appendLine("```")
                appendLine()
            }
        }
    }
}
