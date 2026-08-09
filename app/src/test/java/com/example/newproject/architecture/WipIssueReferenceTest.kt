package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `_wip/` 内から参照される課題IDが、課題台帳に実在することを固定する。
 *
 * ## なぜ要るか
 *
 * 2026-08-08、instrumentation を全34件そろえて実機確認まで終えたのに、
 * **更新したのは課題台帳の本文末尾だけ**だった。一覧・見出し・`roadmap`・
 * `document_map`・設計書冒頭は古い状態のまま残り、読み手が
 * **未実装・全実装・判断待ち・見送り確定の4状態を同時に読む**ことになっていた。
 *
 * 意味的な陳腐化そのものは機械では捕まえられない。だが**この事故の一番痛い形**、
 * すなわち「**台帳から消したIDを他の文書が参照し続ける**」は数えられる。
 * 消し忘れではなく**消したことによる不整合**を検出する。
 *
 * ## 見ているもの
 *
 * `_wip/roadmap.md` と `_wip/feature_ideas.md` の本文に出てくる課題ID表記
 * （`TEST-2` のような形）が、すべて `_wip/current_issues.md` の見出しに実在すること。
 *
 * ## 見ていないもの
 *
 * - **恒久文書（`dev/` `review/`）は対象外。** `_wip/` の項目IDへ依存しない規約があり、
 *   そちらは「参照しないこと」自体が正しい（→ document_map §3）。
 *   ただし `review/findings.md` の `起票` だけは別途 `ReviewFindingsLedgerTest` が見ている。
 * - 表記が古いだけで参照が壊れていない陳腐化（例: 完了済みを「未着手」と書く）。
 *   **これは検査できないので、レビューで拾う。**
 */
class WipIssueReferenceTest {

    @Test
    fun `roadmap と feature_ideas が参照する課題IDは台帳に実在する`() {
        val known = issueIds()
        val violations = referencingFiles().flatMap { file ->
            val text = file.readText()
            ISSUE_ID_PATTERN.findAll(text)
                .filterNot { REVIEW_FINDING_PREFIX.matches(it.groupValues[1]) }
                .map { it.value }
                .filterNot { it in known }
                .distinct()
                .map { "${file.name}: $it が課題台帳に無い" }
        }.sorted()

        assertTrue(
            "`_wip/` から実在しない課題IDを参照しています。" +
                "台帳から消したなら参照側も直してください:\n" + violations.joinToString("\n") +
                "\n（台帳の課題: ${known.sorted().joinToString("・")}）",
            violations.isEmpty()
        )
    }

    private fun referencingFiles(): List<File> =
        listOf("roadmap.md", "feature_ideas.md").map { wipRoot().resolve(it) }.filter { it.isFile }

    private fun issueIds(): Set<String> =
        ISSUE_HEADING.findAll(wipRoot().resolve("current_issues.md").readText())
            .map { it.groupValues[1] }
            .toSet()

    private fun wipRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        val candidates = listOf(
            workingDirectory.resolve("../docs/_wip"),
            workingDirectory.resolve("docs/_wip")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("docs/_wip が見つかりません（作業ディレクトリ: $workingDirectory）")
    }

    private companion object {
        val ISSUE_HEADING = Regex("""^## ([A-Z][A-Z0-9]*-\d+)\.""", RegexOption.MULTILINE)

        /**
         * 課題IDらしき表記。カテゴリ部を group(1) で取る。
         *
         * **英字始まりに限る**のは日付（`2026-08`）に当たらないため。
         * **2文字以上**に限るのは、節番号（`L-3` / `N-7` / `D-1`）を拾わないため。
         *
         * **当初 `{2,}`（3文字以上）にしていて `AI-3` のような2文字カテゴリを取りこぼしていた。**
         * 検査そのものが、守りたい対象より狭い範囲しか数えていなかった（→ lessons L29）。
         */
        val ISSUE_ID_PATTERN = Regex("""\b([A-Z][A-Z0-9]+)-\d+\b""")

        /**
         * レビューの指摘番号（`P2-5` など）は課題IDではないので除く。
         *
         * `_wip/` からレビューの指摘を引用することは正当で、
         * これを課題IDと見なすと**正しい記述が落ちる**。
         */
        val REVIEW_FINDING_PREFIX = Regex("""P\d+""")
    }
}
