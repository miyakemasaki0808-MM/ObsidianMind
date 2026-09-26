package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本番のプロンプト builder が、**実生成テストで通っているか通っていないかを必ず宣言させる。**
 *
 * ## なぜ要るか
 *
 * `OnDeviceGenerationTest` は「本番の各プロンプトで `generate()`」と書きながら、
 * 実際に通していたのは **10 builder のうち4つ**だった。
 * Nano依存の instrumentation が9件あることと合わせて、
 * **全経路が守られているように読める**状態になっていた（2026-08-08 の外部レビュー指摘）。
 *
 * 主張を「代表4経路」へ狭めたが、**それだけでは11個目の builder が増えたときに同じ穴が空く。**
 * 増えた builder は、通すか通さないかを誰も決めないまま「未保証だが列挙もされていない」へ落ちる。
 *
 * そこで**分類を強制する**。builder を足したら、実生成テストへ足すか、
 * 未保証として明示的に列挙するかのどちらかをしない限りこの検査が落ちる。
 *
 * ## 見ているもの
 *
 * `PromptBuilder` の `fun build*Prompt` すべてが、
 * `OnDeviceGenerationTest` の中で**呼ばれている**か、
 * 同テストの `UNCOVERED_BUILDERS` に**列挙されている**かのどちらかであること。
 *
 * ## 見ていないもの
 *
 * **「呼んでいる」の中身は見ない。** 引数が本番相当かどうかまでは判定しない
 * （それは instrumentation 側の責任）。ここが数えるのは**分類の網羅**だけである。
 */
class PromptGenerationCoverageTest {

    @Test
    fun `本番の全プロンプトが実生成テストで覆われているか未保証として列挙されている`() {
        val builders = productionBuilders()
        val covered = coveredBuilders()
        val declaredUncovered = declaredUncoveredBuilders()

        val unclassified = (builders - covered - declaredUncovered).sorted()
        assertTrue(
            "分類されていないプロンプト builder があります。" +
                "`OnDeviceGenerationTest` で実際に呼ぶか、同テストの UNCOVERED_BUILDERS へ足してください:\n" +
                unclassified.joinToString("\n"),
            unclassified.isEmpty()
        )
    }

    /** 未保証リストに、もう存在しない／実は覆っている builder が残らないようにする。 */
    @Test
    fun `未保証として列挙された builder が実在し、かつ覆われていない`() {
        val builders = productionBuilders()
        val covered = coveredBuilders()

        val ghosts = declaredUncoveredBuilders().filterNot { it in builders }.sorted()
        assertTrue("実在しない builder が未保証リストにあります:\n${ghosts.joinToString("\n")}", ghosts.isEmpty())

        val contradictions = declaredUncoveredBuilders().filter { it in covered }.sorted()
        assertTrue(
            "未保証と書いてあるのに実生成テストで呼んでいます:\n${contradictions.joinToString("\n")}",
            contradictions.isEmpty()
        )
    }

    /** 数え違いを防ぐため、件数そのものも固定する（8と数えて誤った経緯がある）。 */
    @Test
    fun `プロンプト builder は12個ある`() {
        assertEquals(
            "builder の増減は分類の見直しを伴う。件数を更新する前に、覆うか未保証かを決めること。\n" +
                productionBuilders().sorted().joinToString("\n"),
            12,
            productionBuilders().size
        )
    }

    // --- 読み取り -------------------------------------------------------------

    /** `internal` 修飾やインデントの違いに引っかからないよう、修飾子を問わず拾う。 */
    private fun productionBuilders(): Set<String> =
        BUILDER_DECLARATION.findAll(sourceRoot().resolve(PROMPT_BUILDER).readText())
            .map { it.groupValues[1] }
            .toSet()

    private fun generationTestSource(): String = sourceRoot().resolve(GENERATION_TEST).readText()

    private fun coveredBuilders(): Set<String> {
        val body = generationTestSource().substringBefore(UNCOVERED_MARKER)
        return BUILDER_CALL.findAll(body).map { it.groupValues[1] }.toSet()
    }

    private fun declaredUncoveredBuilders(): Set<String> {
        val block = generationTestSource().substringAfter(UNCOVERED_MARKER, "")
            .substringBefore(")")
        return BUILDER_NAME_LITERAL.findAll(block).map { it.groupValues[1] }.toSet()
    }

    private fun sourceRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        val candidates = listOf(workingDirectory.resolve("src"), workingDirectory.resolve("app/src"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("app/src が見つかりません（作業ディレクトリ: $workingDirectory）")
    }

    private companion object {
        const val PROMPT_BUILDER = "main/java/com/example/newproject/ai/PromptBuilder.kt"
        const val GENERATION_TEST = "androidTest/java/com/example/newproject/ai/OnDeviceGenerationTest.kt"

        /** 未保証リストの開始位置。テスト側と同じ綴りにしておくこと。 */
        const val UNCOVERED_MARKER = "val UNCOVERED_BUILDERS = setOf("

        val BUILDER_DECLARATION = Regex("""\bfun\s+(build[A-Za-z]*Prompt)\s*\(""")
        val BUILDER_CALL = Regex("""PromptBuilder\.(build[A-Za-z]*Prompt)\s*\(""")
        val BUILDER_NAME_LITERAL = Regex("""["'](build[A-Za-z]*Prompt)["']""")
    }
}
