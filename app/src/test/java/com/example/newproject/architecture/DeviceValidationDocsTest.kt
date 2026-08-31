package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codex実機検証の入口と、機能別ケースの最低限の形を固定する。 */
class DeviceValidationDocsTest {

    @Test
    fun `共通手順は権限準備検証後処理記録を持つ`() {
        val text = validationDir().resolve("README.md").readText()
        val required = listOf(
            "## 権限範囲",
            "## 実機検証前",
            "## 実機検証中",
            "## 実機検証後",
            "## 記録",
            "途中で1操作ずつ確認を取り直さず"
        )

        val missing = required.filterNot(text::contains)
        assertTrue("実機検証の共通手順に必須項目がありません: ${missing.joinToString()}", missing.isEmpty())
    }

    @Test
    fun `機能別ケースは正本と前後処理を持つ`() {
        val expected = setOf(
            "reflect_distill.md",
            "background_ai_ux.md",
            "note_image_rendering.md",
            "ai_input_budget.md",
            "reunion_card.md",
            "reading_trace_backup.md",
            "booklet_mode.md"
        )
        val actual = validationDir().listFiles { file -> file.extension == "md" && file.name != "README.md" }
            .orEmpty()
            .associateBy { it.name }
        val missingFiles = expected - actual.keys
        val requiredHeadings = listOf("## 正本", "## 適用条件", "## 検証前", "## ケース", "## 後処理", "## 記録")
        val malformed = actual.values.mapNotNull { file ->
            val text = file.readText()
            val missing = requiredHeadings.filterNot(text::contains)
            when {
                missing.isNotEmpty() -> "${file.name}: ${missing.joinToString()}"
                "../../dev/" !in text -> "${file.name}: devの正本へのリンクが無い"
                else -> null
            }
        }

        assertTrue("不足している機能別ケース: ${missingFiles.sorted().joinToString()}", missingFiles.isEmpty())
        assertTrue("機能別ケースの形が不完全です:\n${malformed.joinToString("\n")}", malformed.isEmpty())
    }

    /**
     * **ケースIDは1回だけ現れる。**
     *
     * 実機レビュー本文はケースIDで結果を対応させるので、同じIDが2行あると
     * **どちらを実施したのか記録から復元できない**。実際に冊子のケースで、
     * 同じ内容の行が同じIDで2つ並んだ。手で追記する限り再発するので、機械で数える。
     */
    @Test
    fun `機能別ケースのIDは重複しない`() {
        val duplicates = caseFiles().flatMap { file ->
            CASE_ROW.findAll(file.readText())
                .map { it.groupValues[1] }
                .toList()
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .map { "${file.name}: $it" }
        }.sorted()

        assertTrue("同じケースIDが複数行あります:\n${duplicates.joinToString("\n")}", duplicates.isEmpty())
    }

    /**
     * **instrumentation の件数を書くなら、実数と合っていること。**
     *
     * 「すべて成功」に件数を添えた期待は、テストを足した瞬間に古くなる。
     * 実機側は書いてある数を信じて突き合わせるので、**合わない数は誤判定を生む**。
     */
    @Test
    fun `実機ケースが書くinstrumentationの件数は実数と一致する`() {
        val violations = caseFiles().flatMap { file ->
            INSTRUMENTATION_ROW.findAll(file.readText()).mapNotNull { match ->
                val (className, expected) = match.destructured
                val actual = testMethodCount(className)
                when {
                    actual == null -> "${file.name}: $className が androidTest に見つかりません"
                    actual != expected.toInt() ->
                        "${file.name}: $className は ${expected}件と書かれているが実数は ${actual}件"
                    else -> null
                }
            }.toList()
        }.sorted()

        assertTrue("実機ケースの件数が実数と合っていません:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    private fun testMethodCount(className: String): Int? =
        repositoryRoot().resolve("app/src/androidTest").walkTopDown()
            .firstOrNull { it.isFile && it.name == "$className.kt" }
            ?.let { TEST_ANNOTATION.findAll(it.readText()).count() }

    private fun caseFiles(): List<File> =
        validationDir().listFiles { file -> file.extension == "md" && file.name != "README.md" }
            .orEmpty()
            .sortedBy { it.name }

    @Test
    fun `Codex実機検証の入口が旧運用へ戻っていない`() {
        val root = repositoryRoot()
        val claude = root.resolve("CLAUDE.md").readText()
        val reviewReadme = root.resolve("docs/review/README.md").readText()
        val documentMap = root.resolve("docs/dev/document_map.md").readText()

        assertTrue("CLAUDE.mdから実機手順へ到達できません", "docs/review/device_validation/README.md" in claude)
        assertFalse("旧『ユーザーがAndroid Studioで実施』へ戻っています", "実機確認はユーザーがAndroid Studioで実施する" in claude)
        assertTrue("reviewの入口から実機手順へ到達できません", "device_validation/" in reviewReadme)
        assertTrue("文書地図から実機手順へ到達できません", "review/device_validation/" in documentMap)
    }

    private fun validationDir(): File = repositoryRoot().resolve("docs/review/device_validation").also {
        assertTrue("docs/review/device_validation がありません", it.isDirectory)
    }

    private companion object {
        /** `| \`CASE-01\` | … |` の形のケース行。**表の行だけを数える**（本文中の参照は数えない）。 */
        val CASE_ROW = Regex("""^\| `([A-Z][A-Z0-9]*-\d+)` \|""", RegexOption.MULTILINE)

        /** `` `XxxTest` `` … `N件` を書いた行。 */
        val INSTRUMENTATION_ROW = Regex("""`(\w+Test)`[^|]*\|[^|]*?(\d+)件""")

        val TEST_ANNOTATION = Regex("""@Test\b""")
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = listOf(workingDirectory.resolve(".."), workingDirectory)
        return candidates.firstOrNull { it.resolve("CLAUDE.md").isFile }
            ?: error("リポジトリルートが見つかりません（作業ディレクトリ: $workingDirectory）")
    }
}
