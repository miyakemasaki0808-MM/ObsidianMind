package com.example.newproject.ai

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.newproject.MainActivity
import com.example.newproject.testing.SummaryBaselineItem
import com.example.newproject.testing.SummaryBaselinePlan
import com.example.newproject.testing.SummaryCoverageReport
import com.example.newproject.testing.measureSummaryCoverage
import com.example.newproject.testing.planSummaryBaseline
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.internal.GenAiUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **固定コーパスを本番のプロンプトで生成させ、出力を物差しに掛けて記録する。**
 *
 * 正本は [ai_quality_measurement](../../../../../../../../docs/dev/system/ai_quality_measurement.md)。
 * 物差しそのもの（`SummaryCoverage`）と閾値の較正はJVM側が持つので、ここが足すのは
 * **「実際に Nano が返す要約はどう出るのか」だけ**である。
 *
 * ## 何を主張し、何を主張しないか
 *
 * **主張する:** 選んだ組のすべてで生成が返り、採点が例外なく通ること。
 *
 * **主張しない: 採点の値と、変種の優劣。** 値は logcat へ出し、人が読む。
 * 実機の出力では忠実な文と誤りの文を閾値で分離できない（JVMの較正テストが固定している）ので、
 * 「裏付けが弱い」は手がかりであって合否ではない。変種で入力が変わるのは長文3本だけで、
 * **1回の表は探索の記録に留める。**
 *
 * ## 何を生成し、何を使い回すか
 *
 * 計画は `SummaryBaselinePlan` が決め、JVMの `SummaryBaselinePlanTest` が固定している。
 * **生成は決定的なので、同じプロンプトの組は同じ実行の中で先に生成した応答を使い回す**
 * （全27組のうち生成は15回）。使い回した組は `PLAN` 行と `RESULT` 行に明示する。
 *
 * ## 引数（すべて省略可）
 *
 * | 引数 | 意味 |
 * |---|---|
 * | `-e variants production,head_only` | 変種を絞る（`production` / `head_only` / `budget_x2`） |
 * | `-e notes 0400_tiny_memo,0900_middle_key` | ノートを絞る（`.md` を付けない） |
 * | `-e keys budget_x2/0200_long_nested,...` | 組をそのまま指定する。**`RESUME` 行の値をそのまま渡す**。variants / notes とは混ぜない |
 *
 * **知らない名前は失敗させる。** 綴りの誤りが「0組を採って成功」に見えないようにするため。
 *
 * ## 1回の実行で生成してよい回数
 *
 * **AICore は短い時間窓の中の回数で要求を断る。** 2026-09-13 の実機では2回とも、推論が12回成功した
 * 直後の13回目が `ErrorCode 9 / BUSY` になり、拒否は直前の成功の32ms後に返った（モデルは動いていない）。
 * 時間窓の長さは AICore の内部にあり、アプリからは変えられないので**特定しない**。
 * **1回の実行の生成を12回以内に計画し、足りなければ時間を空けて分ける**（全組は生成15回なので2回に分かれる）。
 *
 * ## 途中で失敗したとき
 *
 * **失敗を成功や skip へ読み替えない。** その組を `FAILED` 行へ出し、**失敗した組を含む残り**を
 * `RESUME` 行へ出してテストを失敗させる。成功した組の `RESULT` と `RESPONSE` はそれまでに出ているので失われない。
 * 時間を空けてから `RESUME` の値を `-e keys` へ渡せば、続きから採れる。
 *
 * ## 行の形（タグ `SummaryCoverageBaseline`、区切りはタブ）
 *
 * - `PLAN	<key>	generate` / `PLAN	<key>	reuse:<元のkey>`
 * - `RESULT	<key>	<文数>	<裏付け最小>	<裏付け平均>	<触れた節>/<計測対象の節>	<生成ms または reused>`
 * - `RESPONSE	<key>	<応答の1行>`（応答の全行。切らない）
 * - `FAILED	<key>	<例外>` / `RESUME	<失敗した組を含む残りの key,key,...>`
 *
 * ## Activity が要る
 *
 * AICore はバックグラウンドからの利用を拒否する（`ErrorCode 30`）。
 */
@RunWith(AndroidJUnit4::class)
class SummaryCoverageBaselineTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val client = AICoreClient(
        isDeviceCapable = {
            GenAiUtils.isAiCoreCompatible(
                InstrumentationRegistry.getInstrumentation().targetContext
            )
        }
    )

    /**
     * **Nano を使わずに落ちる側を先に置く。**
     *
     * assets が test APK へ入っていなければ、生成を1件も走らせないうちに分かる。
     */
    @Test
    fun 固定コーパスを実機から読める() {
        val notes = corpusNotes()

        assertTrue(
            "固定コーパスを test APK から読めない。androidTest/assets/$CORPUS_DIR を確認すること。",
            notes.size >= EXPECTED_CORPUS_SIZE
        )
        notes.forEach { (name, content) ->
            assertTrue("$name の中身が空だった", content.isNotBlank())
        }
        Log.i(TAG, "固定コーパス ${notes.size}本: ${notes.keys.joinToString()}")
    }

    /**
     * skip 判定は生の [FeatureStatus] だけで行う（`checkAvailability()` は例外を値へ畳むので使わない）。
     * **生成が投げた例外は skip せずそのまま失敗させる。**
     */
    @Test
    fun 要約の基準線を採る() = runBlocking<Unit> {
        val status = client.featureStatus()
        assumeTrue(
            "端末AIが利用可能ではないため基準線を飛ばす（FeatureStatus=$status）。",
            status == FeatureStatus.AVAILABLE
        )

        val arguments = InstrumentationRegistry.getArguments()
        val notes = corpusNotes()
        val plan = planSummaryBaseline(notes).select(
            arguments.getString(ARG_VARIANTS),
            arguments.getString(ARG_NOTES),
            arguments.getString(ARG_KEYS)
        )
        logPlan(plan)

        val responses = mutableMapOf<String, String>()
        val reports = mutableMapOf<String, SummaryCoverageReport>()
        plan.items.forEach { item ->
            val source = plan.sourceOf(item)
            try {
                val elapsed = if (source.key != item.key) {
                    null
                } else {
                    val started = SystemClock.elapsedRealtime()
                    val response = client.generate(item.prompt)
                    assertTrue(
                        "${item.key} の生成が空だった。SDK制約・プロンプト長・API互換のいずれかを疑う。",
                        response.isNotBlank()
                    )
                    responses[item.key] = response
                    SystemClock.elapsedRealtime() - started
                }
                val response = responses.getValue(source.key)
                val report = measureSummaryCoverage(notes.getValue("${item.note}$MARKDOWN_SUFFIX"), response)
                reports[item.key] = report
                logResult(item, source, response, report, elapsed)
            } catch (failure: Throwable) {
                Log.i(TAG, "FAILED\t${item.key}\t${failure.javaClass.simpleName}: ${failure.message?.lineSequence()?.firstOrNull()}")
                Log.i(TAG, "RESUME\t${plan.resumeKeysAfter(item.key).joinToString(",")}")
                throw failure
            }
        }

        logComparison(plan, reports)
    }

    private fun logPlan(plan: SummaryBaselinePlan) {
        Log.i(
            TAG,
            "計画: ${plan.items.size}組 / 生成 ${plan.generations.size}回 / 使い回し " +
                "${plan.items.size - plan.generations.size}組" +
                if (plan.generations.size > GENERATIONS_PER_RUN) {
                    "（生成が${GENERATIONS_PER_RUN}回を超える。途中で BUSY になりうるので、RESUME で分けて採る）"
                } else ""
        )
        plan.items.forEach { item ->
            val source = plan.sourceOf(item)
            Log.i(TAG, "PLAN\t${item.key}\t" + if (source.key == item.key) "generate" else "reuse:${source.key}")
        }
    }

    private fun logResult(
        item: SummaryBaselineItem,
        source: SummaryBaselineItem,
        response: String,
        report: SummaryCoverageReport,
        elapsedMs: Long?
    ) {
        val supports = report.sentences.map { it.support }
        Log.i(
            TAG,
            listOf(
                "RESULT",
                item.key,
                report.sentences.size.toString(),
                supports.minOrNull().format(),
                (if (supports.isEmpty()) null else supports.average()).format(),
                "${report.sections.count { it.touched }}/${report.sections.count { it.measured }}",
                elapsedMs?.toString() ?: "reused:${source.key}"
            ).joinToString("\t")
        )
        response.lineSequence().forEach { Log.i(TAG, "RESPONSE\t${item.key}\t$it") }
        report.unsupportedSentences.forEach { Log.i(TAG, "    [裏付けが弱い] $it") }
        report.untouchedSections.forEach { Log.i(TAG, "    [未反映] $it") }
    }

    /**
     * **比べてよいノートだけを、ノートごとに並べる。合計は出さない。**
     * 入力が全変種で同じノートを合計に混ぜると、差の出ようがない組が結論を引っ張る。
     * 文数も並べる — 被覆は文数で変わるので、文数が違う行どうしの被覆の差は品質の差ではない。
     */
    private fun logComparison(plan: SummaryBaselinePlan, reports: Map<String, SummaryCoverageReport>) {
        Log.i(TAG, "── 比較（単回・探索の記録。優劣は判定しない）──")
        if (plan.identicalInputNotes.isNotEmpty()) {
            Log.i(TAG, "入力が全変種で同じ（比較しない）: ${plan.identicalInputNotes.joinToString()}")
        }
        plan.comparableNotes.forEach { note ->
            plan.items.filter { it.note == note }.forEach { item ->
                val report = reports[item.key] ?: return@forEach
                Log.i(
                    TAG,
                    "COMPARE\t$note\t${item.variant.key}\t文数 ${report.sentences.size}\t" +
                        "裏付け平均 ${report.sentences.map { it.support }.let { if (it.isEmpty()) null else it.average() }.format()}\t" +
                        "被覆 ${report.sections.count { it.touched }}/${report.sections.count { it.measured }}"
                )
            }
        }
    }

    private fun Double?.format(): String = if (this == null) "—" else "%.3f".format(this)

    /** test APK 側の assets から読む（対象アプリではなくテスト自身が持っている）。`ファイル名` → 本文。 */
    private fun corpusNotes(): Map<String, String> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return assets.list(CORPUS_DIR).orEmpty()
            .filter { it.endsWith(MARKDOWN_SUFFIX) && it != "README.md" }
            .sorted()
            .associateWith { name ->
                assets.open("$CORPUS_DIR/$name").bufferedReader().use { it.readText() }
            }
    }

    private companion object {
        const val TAG = "SummaryCoverageBaseline"
        const val CORPUS_DIR = "ai_corpus"
        const val MARKDOWN_SUFFIX = ".md"
        const val ARG_VARIANTS = "variants"
        const val ARG_NOTES = "notes"
        const val ARG_KEYS = "keys"

        /** 1回の実行で生成してよい回数。実機で2回とも13回目が BUSY になった観測値（→ KDoc）。 */
        const val GENERATIONS_PER_RUN = 12

        /** 揃っていることの下限。減らすときは正本の固定コーパスの節も直す。 */
        const val EXPECTED_CORPUS_SIZE = 9
    }
}
