package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部レビューの指摘が、1件も取りこぼされずに受付簿へ載っていることをCIで固定する。
 *
 * ## なぜテストにするのか
 *
 * 「総評で問題と書いたものは課題台帳へ起票する」という規則は `CLAUDE.md` と
 * `docs/dev/document_map.md` に以前からあった。**それでも 2026-08-01 の総評の指摘4件が
 * 2日間どこにも追跡されなかった。** 規則を2箇所へ書いても守られなかったので、
 * **規則を増やすのではなく、守られなかったことを検出できる形にした。**
 *
 * スクリプトではなくJVMテストにしたのは、**スクリプトだと「走らせる」という
 * 手動契約が新しく生まれ、同じ罠を1段ずらすだけになる**ため。テストなら
 * `./gradlew testDebugUnitTest` と CI に自動で載る。
 *
 * ## 何を守るか
 *
 * 1. レビュー本文の指摘（`### P1-1.` 等）すべてに受付行がある
 * 2. 受付行の処遇が空でない
 * 3. 処遇は決めた5語のいずれか
 * 4. `起票` は**実在する課題IDを参照する**
 * 5. 受付簿に、存在しない指摘の行が無い（消したレビューの残骸を残さない）
 *
 * **新しいレビューを足して受付簿を更新し忘れると、1 で落ちる。**
 */
class ReviewFindingsLedgerTest {

    @Test
    fun `レビューの指摘はすべて受付簿に載っている`() {
        val missing = (reviewFindingIds() - ledgerIds()).sorted()
        assertTrue(
            "受付簿に無いレビュー指摘があります。docs/review/findings.md へ1行ずつ足してください:\n" +
                missing.joinToString("\n"),
            missing.isEmpty()
        )
    }

    @Test
    fun `受付簿に実在しない指摘の行が無い`() {
        val stale = (ledgerIds() - reviewFindingIds()).sorted()
        assertTrue(
            "レビュー本文に対応する指摘が無い受付行があります:\n${stale.joinToString("\n")}",
            stale.isEmpty()
        )
    }

    @Test
    fun `処遇は空でなく、決めた語のいずれかである`() {
        val violations = ledgerRows().mapNotNull { (id, disposition) ->
            when {
                disposition.isBlank() -> "$id: 処遇が空"
                ALLOWED_DISPOSITIONS.none { disposition.startsWith(it) } ->
                    "$id: 未定義の処遇「${disposition.take(20)}」" +
                        "（使えるのは ${ALLOWED_DISPOSITIONS.joinToString("・")}）"
                else -> null
            }
        }.sorted()
        assertTrue("受付簿の処遇に問題があります:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    /**
     * `起票` と書いたら、その課題が課題台帳に実在すること。
     *
     * **ここが受付簿の本体。** 「起票した」と書いてあるのに台帳へ無い、という
     * 今回の失敗そのものを検出する。
     */
    @Test
    fun `起票した指摘は実在する課題IDを参照する`() {
        val known = issueIds()
        val violations = ledgerRows()
            .filter { (_, disposition) -> disposition.startsWith("`起票`") }
            .mapNotNull { (id, disposition) ->
                val referenced = ISSUE_ID_PATTERN.findAll(disposition).map { it.value }.toList()
                when {
                    referenced.isEmpty() -> "$id: 起票と書いてあるが課題IDが無い"
                    referenced.none { it in known } ->
                        "$id: 参照している課題 ${referenced.joinToString("・")} が台帳に無い" +
                            "（台帳の課題: ${known.sorted().joinToString("・")}）"
                    else -> null
                }
            }.sorted()
        assertTrue(
            "起票の参照先が課題台帳と合っていません:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    // --- 読み取り -------------------------------------------------------------

    /** レビュー本文の指摘ID。`2026-08-01-no9.md` の `### P1-1.` → `2026-08-01/P1-1`。 */
    private fun reviewFindingIds(): Set<String> =
        reviewDir().listFiles { f: File -> f.name.matches(REVIEW_FILE) }
            .orEmpty()
            .flatMap { file ->
                val date = REVIEW_FILE.find(file.name)!!.groupValues[1]
                FINDING_HEADING.findAll(file.readText()).map { "$date/${it.groupValues[1]}" }
            }
            .toSet()

    /** 受付簿の行。`| \`2026-08-01/P1-1\` | 指摘 | 処遇 |` を (ID, 処遇) に分解する。 */
    private fun ledgerRows(): List<Pair<String, String>> =
        LEDGER_ROW.findAll(reviewDir().resolve("findings.md").readText())
            .map { it.groupValues[1] to it.groupValues[3].trim() }
            .toList()

    private fun ledgerIds(): Set<String> = ledgerRows().map { it.first }.toSet()

    /** 課題台帳の見出しから課題IDを拾う（`## TRACE-1. ...` → `TRACE-1`）。 */
    private fun issueIds(): Set<String> =
        ISSUE_HEADING.findAll(docsRoot().resolve("_wip/current_issues.md").readText())
            .map { it.groupValues[1] }
            .toSet()

    private fun reviewDir(): File = docsRoot().resolve("review")

    private fun docsRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        val candidates = listOf(
            workingDirectory.resolve("../docs"),
            workingDirectory.resolve("docs")
        )
        return candidates.firstOrNull { it.resolve("review").isDirectory }
            ?: error("docs/review が見つかりません（作業ディレクトリ: $workingDirectory）")
    }

    private companion object {
        val REVIEW_FILE = Regex("""^(\d{4}-\d{2}-\d{2})-.+\.md$""")
        val FINDING_HEADING = Regex("""^### (P\d+-\d+)\.""", RegexOption.MULTILINE)
        val LEDGER_ROW = Regex("""^\| `(\d{4}-\d{2}-\d{2}/P\d+-\d+)` \|([^|]*)\|([^|]*)\|""", RegexOption.MULTILINE)
        val ISSUE_HEADING = Regex("""^## ([A-Z0-9]+-\d+)\.""", RegexOption.MULTILINE)
        val ISSUE_ID_PATTERN = Regex("""\b[A-Z0-9]+-\d+\b""")

        /** 許す処遇。**これ以外を書いたら落とす**（曖昧な処遇で追跡が途切れるのを防ぐ）。 */
        val ALLOWED_DISPOSITIONS = listOf("`起票`", "`統合`", "`解消`", "`見送り`", "`誤検知`")
    }
}
