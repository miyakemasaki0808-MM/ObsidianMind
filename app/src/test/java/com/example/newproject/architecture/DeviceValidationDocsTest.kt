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
        val actual = caseFiles().associateBy { it.name }
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
    /**
     * **「いまどこまで実機で見るか」の上限を、正本・課題台帳・ケース表で揃える。**
     *
     * ケースを1つ足したとき、**ケース表と台帳だけが追いついて正本が旧い上限のまま残った**
     * （2026-09-05 の再レビュー）。そのまま実機担当へ渡すと、
     * **足したばかりのケースを実行せずに完了扱いにできる。**
     *
     * **見るのは上限の一致だけ。** 過去版の実績として書かれた範囲は上限より小さいので素通りする
     * （歴史は歴史として残す）。**中身が正しいかは見ない**（→ docs/dev/lessons/L55.md）。
     */
    @Test
    fun `冊子の実機ケースの上限は正本と課題台帳で一致する`() {
        val cases = repositoryRoot().resolve("docs/review/device_validation/booklet_mode.md")
        val sources = mapOf(
            "実機ケース" to cases,
            "正本（features/booklet_mode.md）" to repositoryRoot().resolve("docs/dev/features/booklet_mode.md"),
            "課題台帳（_wip/current_issues.md）" to repositoryRoot().resolve("docs/_wip/current_issues.md")
        )

        // **課題台帳は、冊子の課題が開いている間だけ数える。**
        // 課題が閉じれば台帳は冊子ケースに触れなくなる（2026-09-07 にめくりの2件が閉じた）。
        // **触れていない台帳を「ずれている」と数えると、閉じた瞬間に落ちる検査になる。**
        // 触れている限りは今までどおり一致を要求する。
        val ledger = "課題台帳（_wip/current_issues.md）"
        val highest = sources.mapNotNull { (name, file) ->
            val number = highestCaseNumber(file)
            when {
                number != null -> name to number
                name == ledger -> null
                else -> error("冊子のケース番号が見つかりません: $name")
            }
        }.toMap()
        val expected = highest.getValue("実機ケース")
        val stale = highest.filterValues { it != expected }

        assertTrue(
            "冊子の実機ケースの上限がずれています。ケースを足したら、参照している側も揃えてください" +
                "（ケース表: $expected）:\n" +
                stale.entries.joinToString("\n") { (name, value) -> "$name: $value" },
            stale.isEmpty()
        )
    }

    /** その文書が触れている冊子ケース番号の最大値。**現在有効な上限**を表す。 */
    private fun highestCaseNumber(file: File): Int? {
        require(file.isFile) { "文書が見つかりません: $file" }
        return BOOKLET_CASE.findAll(file.readText())
            .map { it.groupValues[1].toInt() }
            .maxOrNull()
    }

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

    /**
     * 機能別ケースだけを返す。**手順書は含めない。**
     *
     * `README.md`（共通手順）と `quick_check.md`（簡易版）は再現手順の表を持たないので、
     * 「正本・適用条件・検証前・ケース・後処理・記録」の形を要求すると通らない。
     * **形の検査はそれぞれの役割ごとに別の @Test が持つ。**
     */
    private fun caseFiles(): List<File> =
        validationDir().listFiles { file -> file.extension == "md" && file.name !in PROCEDURE_DOCS }
            .orEmpty()
            .sortedBy { it.name }

    /**
     * **簡易版のスモークセットが、実在するケースだけを指していることを固定する。**
     *
     * ## なぜ要るか
     *
     * 簡易版は「通すケースを絞る」ことで成り立つので、**絞った先が実在しなければ
     * 何も通さないまま緑になる。** ケースIDは後から足されもすれば消えもするが、
     * **文書側には何も起きない**（番号をここへ書くと課題IDの走査に当たるので、例は挙げない）。
     *
     * 機能別ケースを1本足したときにスモークを決め忘れる形も同じで、
     * **決め忘れは「簡易版の射程外」と区別できない**ので、表への登録を強制する。
     *
     * ## 見ているもの
     *
     * 1. 簡易版の手順書が、選抜と観測手段の節を持つ
     * 2. スモーク表のIDが、そのケース表に**行として実在する**
     * 3. **すべての機能別ケースがスモーク表に1行ある**
     *
     * ## 見ていないもの
     *
     * **選んだIDが妥当かは見ない。** 妥当さは選抜規則を人が当てて決める（→ quick_check.md §選抜規則）。
     */
    @Test
    fun `簡易版のスモークセットは実在するケースを指す`() {
        val quick = validationDir().resolve("quick_check.md")
        assertTrue("簡易版の手順書がありません: $quick", quick.isFile)
        val text = quick.readText()

        val requiredHeadings = listOf(
            "## 使う場面",
            "## 選抜規則",
            "## スモークセット",
            "## 観測手段",
            "## 後処理",
            "## 記録"
        )
        val missingHeadings = requiredHeadings.filterNot(text::contains)
        assertTrue("簡易版の手順書に必須の節がありません: ${missingHeadings.joinToString()}", missingHeadings.isEmpty())

        val rows = SMOKE_ROW.findAll(text).associate { match ->
            val (fileName, ids) = match.destructured
            fileName to CASE_ID.findAll(ids).map { it.groupValues[1] }.toList()
        }

        val unknownIds = rows.flatMap { (fileName, ids) ->
            val caseFile = validationDir().resolve(fileName)
            if (!caseFile.isFile) return@flatMap listOf("$fileName: ケース表が見つかりません")
            val defined = CASE_ROW.findAll(caseFile.readText()).map { it.groupValues[1] }.toSet()
            ids.filterNot(defined::contains).map { "$fileName: `$it` は表に無い" }
        }.sorted()
        assertTrue("簡易版が実在しないケースを指しています:\n${unknownIds.joinToString("\n")}", unknownIds.isEmpty())

        val emptyRows = rows.filterValues { it.isEmpty() }.keys.sorted()
        assertTrue("スモークIDが1件も無い行があります: ${emptyRows.joinToString()}", emptyRows.isEmpty())

        val unlisted = (caseFiles().map { it.name }.toSet() - rows.keys).sorted()
        assertTrue(
            "スモークセットに載っていない機能別ケースがあります（射程外なら、そう書いた行を足すこと）: " +
                unlisted.joinToString(),
            unlisted.isEmpty()
        )
    }

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
        /** 手順を書く文書。**機能別ケースの形を当てない。** */
        val PROCEDURE_DOCS = setOf("README.md", "quick_check.md")

        /** 簡易版のスモーク行。`| 機能 | [file.md](file.md) | \`ID\` \`ID\` |` の3列目からIDを拾う。 */
        val SMOKE_ROW = Regex("""^\| [^|]+ \| \[([a-z_]+\.md)\]\([^)]+\) \|([^|]+)\|""", RegexOption.MULTILINE)

        /** 冊子の実機ケースID。**番号だけを取り、文字列としては組み立てない**（課題IDの走査に当たるため）。 */
        val BOOKLET_CASE = Regex("""BOOK-(\d{2})""")

        /** バッククォートで囲まれたケースID。スモーク行の3列目から拾う。 */
        val CASE_ID = Regex("""`([A-Z][A-Z0-9]*-\d+[a-z]?)`""")

        /** `| \`CASE-01\` | … |` の形のケース行。**表の行だけを数える**（本文中の参照は数えない）。 */
        val CASE_ROW = Regex("""^\| `([A-Z][A-Z0-9]*-\d+[a-z]?)` \|""", RegexOption.MULTILINE)

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
