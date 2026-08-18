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
 * 3. 処遇は未解決を表す `起票`・`統合` のいずれか
 * 4. 受付行は**実在する課題IDを参照する**（参照した**すべて**が実在すること）
 * 5. 作業ツリーに置くレビュー本文は最新の1本だけ
 * 6. 指摘IDにも受付行にも**重複が無い**
 *
 * **新しいレビューを足して受付簿を更新し忘れると、1 で落ちる。**
 *
 * ## 2026-08-05 — 「足す」形の変異を試していなかった
 *
 * 導入時の変異検証6件はすべて落ちたが、**いずれも既存データを壊す形だった**ため、
 * **新しいデータを足したときに潰れる欠陥を2つ見逃していた。**
 *
 * - **受付IDが日付部分だけだった。** 同じ日に2本目のレビュー（`2026-08-03-other.md`）を
 *   置くと、その `P1-1` が既存の `2026-08-03-image-n3/P1-1` と**同じIDへ潰れ**、
 *   受付簿を更新しなくても 1 が通った。IDは**ファイルstem全体**にし、
 *   さらに 6 で潰れ自体を検出する（潰れたことに気づけないのが根だった）。
 * - **`起票` の参照先が1件でも実在すれば通っていた。** `none { it in known }` は
 *   実在する課題IDと架空のIDが並んだ行（`実在ID / TYPO-999`）を成功にする。
 *   **すべてを検証する。**
 *
 * **変異の型に「足す」を含める。** 既存行を壊す変異だけでは、この2つは永久に出ない。
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

    /**
     * **0本を許すのは、レビュー本文を履歴へ残さないため**（`.gitignore`）。
     *
     * 本文には端末の識別子や検証中のローカルパスが入るので、作業ツリー限りで扱う。
     * 新規チェックアウトでは0本になるが、**溜めない**という守りたい性質は `<= 1` で保たれる。
     * **指摘の存在と処遇は受付簿が引き受ける**ので、本文が無くても追跡は切れない。
     */
    @Test
    fun `作業ツリーに残すレビュー本文は多くても1本`() {
        val reviewFiles = reviewFiles()
        assertTrue(
            "docs/review のレビュー本文は最新の1本だけ残してください:\n" +
                reviewFiles.joinToString("\n") { it.name },
            reviewFiles.size <= 1
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
     * 受付行がある限り、その課題が課題台帳に実在すること。
     *
     * **ここが受付簿の本体。** 受付後の取りこぼしだけでなく、課題を閉じたのに
     * 古い受付行だけを残すことも検出する。
     */
    @Test
    fun `受付中の指摘は実在する課題IDを参照する`() {
        val known = issueIds()
        val violations = ledgerRows()
            .mapNotNull { (id, disposition) ->
                val referenced = ISSUE_ID_PATTERN.findAll(disposition).map { it.value }.toList()
                // **1件でも実在すれば通る形にしない。** 実在する課題IDと架空のIDが
                // 並んだ行を成功にしてしまい、取りこぼしを見逃す。
                val unknown = referenced.filterNot { it in known }
                when {
                    referenced.isEmpty() -> "$id: 受付中だが課題IDが無い"
                    unknown.isNotEmpty() ->
                        "$id: 参照している課題 ${unknown.joinToString("・")} が台帳に無い" +
                            "（台帳の課題: ${known.sorted().joinToString("・")}）"
                    else -> null
                }
            }.sorted()
        assertTrue(
            "受付中の指摘が課題台帳と合っていません:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    /**
     * 指摘IDが潰れていないこと。
     *
     * **同じIDへ潰れると、受付簿を更新しなくても「すべてに受付行がある」が通る。**
     * 潰れたこと自体に気づけないのが 2026-08-05 の欠陥の根だったので、
     * ID の作り方を直すだけでなく、潰れを**明示的に落とす**。
     */
    @Test
    fun `レビュー本文の指摘IDに重複が無い`() {
        val duplicates = duplicatesOf(reviewFindingIdList())
        assertTrue(
            "同じ指摘IDが複数あります（レビューのファイル名かP番号を見直してください）:\n" +
                duplicates.joinToString("\n"),
            duplicates.isEmpty()
        )
    }

    @Test
    fun `受付簿の受付IDに重複が無い`() {
        val duplicates = duplicatesOf(ledgerRows().map { it.first })
        assertTrue(
            "同じ受付IDの行が複数あります:\n${duplicates.joinToString("\n")}",
            duplicates.isEmpty()
        )
    }

    // --- 読み取り -------------------------------------------------------------

    /**
     * レビュー本文の指摘ID。`2026-08-08-source-state.md` の `### P2-1.` →
     * `2026-08-08-source-state/P2-1`。
     *
     * **日付部分ではなくファイルstem全体を使う。** 日付だけだと、同じ日に2本目の
     * レビューを置いた瞬間に別々の指摘が同じIDへ潰れる。
     */
    private fun reviewFindingIdList(): List<String> =
        reviewFiles()
            .flatMap { file ->
                val stem = REVIEW_FILE.find(file.name)!!.groupValues[1]
                FINDING_HEADING.findAll(file.readText())
                    .map { "$stem/${it.groupValues[1]}" }
                    .toList()
            }

    private fun reviewFindingIds(): Set<String> = reviewFindingIdList().toSet()

    private fun duplicatesOf(ids: List<String>): List<String> =
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()

    /** 受付簿の行。`| \`2026-08-01-no9/P1-1\` | 指摘 | 処遇 |` を (ID, 処遇) に分解する。 */
    private fun ledgerRows(): List<Pair<String, String>> =
        LEDGER_ROW.findAll(reviewDir().resolve("findings.md").readText())
            .map { it.groupValues[1] to it.groupValues[3].trim() }
            .toList()

    private fun ledgerIds(): Set<String> = ledgerRows().map { it.first }.toSet()

    private fun reviewFiles(): List<File> =
        reviewDir().listFiles { f: File -> f.name.matches(REVIEW_FILE) }
            .orEmpty()
            .sortedBy { it.name }

    /** 課題台帳の見出しから課題IDを拾う（`## ABC-1. ...` → `ABC-1`）。 */
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
        /** 捕捉するのは**stem全体**（`2026-08-03-image-n3`）。日付だけだと同じ日で潰れる。 */
        val REVIEW_FILE = Regex("""^(\d{4}-\d{2}-\d{2}-.+)\.md$""")
        val FINDING_HEADING = Regex("""^### (P\d+-\d+)\.""", RegexOption.MULTILINE)
        val LEDGER_ROW =
            Regex("""^\| `(\d{4}-\d{2}-\d{2}-[^`/]+/P\d+-\d+)` \|([^|]*)\|([^|]*)\|""", RegexOption.MULTILINE)
        val ISSUE_HEADING = Regex("""^## ([A-Z][A-Z0-9]*-\d+)\.""", RegexOption.MULTILINE)

        /**
         * 課題ID。**先頭を英字に限る。**
         *
         * `[A-Z0-9]+-\d+` だと処遇欄の日付（`2026-08-05` の `2026-08` の部分）にも
         * 当たる。参照を**すべて**検証する形にした以上、日付を書いただけの
         * `起票` 行が落ちてしまうため、英字始まりに絞る（英数字混在のカテゴリも通る）。
         */
        val ISSUE_ID_PATTERN = Regex("""\b[A-Z][A-Z0-9]*-\d+\b""")

        /** 解消済みの行は残さない。受付簿が許すのは、実在する未解決課題への2経路だけ。 */
        val ALLOWED_DISPOSITIONS = listOf("`起票`", "`統合`")
    }
}
