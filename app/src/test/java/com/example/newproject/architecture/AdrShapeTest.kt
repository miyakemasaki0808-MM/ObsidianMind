package com.example.newproject.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ADR（`docs/dev/decisions/ADR-*.md`）の様式を固定する。
 *
 * **なぜテストにするか。** 「20行以内」という規則は
 * [docs/dev/decisions/README.md] に書いた**その同じコミットで4本とも破られた**。
 * このリポジトリで規則が守られたのは検査に載せたときだけ、というのが実績である
 * （→ docs/dev/lessons.md L29）。**文書に書くだけの規則は増やさない。**
 *
 * **行数は実ファイルの行数で数える。** 旧規則は「20行以内」としか書いておらず、
 * 空行込みか本文だけかが決まっていなかった（本文なら18〜20行で全て収まり、
 * ファイル行数なら26〜28行で全て超過する、という状態だった）。
 * **`wc -l` で誰でも同じ値になる数え方**へ寄せ、上限を30行に置く。
 *
 * 上限の意図は短さそのものではなく、**ADRに設計の写しを置かせないこと**である。
 * 詳細を書き始めると必ず溢れるので、溢れた時点で
 * 「正本は `features/` か `system/` にあるはず」と気づける。
 */
class AdrShapeTest {

    @Test
    fun `ADR は30行以内に収める`() {
        val violations = adrFiles()
            .map { it to it.readLines().size }
            .filter { (_, lines) -> lines > MAX_LINES }
            .map { (file, lines) -> "${file.name}: ${lines}行（上限 $MAX_LINES）" }

        assertTrue(
            "ADRが長すぎます。設計の詳細は features/ か system/ の正本へ移し、" +
                "ADRには文脈・決定・帰結だけを残してください:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `ADR は状態と決定日と詳細の正本を持つ`() {
        val required = listOf("**状態:**", "**決定日:**", "**詳細の正本:**")
        val violations = adrFiles().flatMap { file ->
            val text = file.readText()
            required.filterNot { text.contains(it) }.map { "${file.name}: $it が無い" }
        }

        assertTrue(
            "ADRのヘッダが欠けています（様式は docs/dev/decisions/README.md）:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    /**
     * **恒久文書から `_wip/` の項目IDを参照しない。**
     *
     * `_wip/` はリリース時に廃棄するので、カテゴリ記号つきの項目番号を残すと
     * **廃棄した瞬間に意味が消える**。課題に触れるときは番号ではなく内容そのものを書く。
     *
     * ここで ADR だけを対象にするのは、ADRが**最も寿命の長い文書**だからである
     * （`features/` `system/` へ広げるのは、既存の違反を片付けてから）。
     */
    @Test
    fun `ADR は _wip の項目IDを参照しない`() {
        val violations = adrFiles().flatMap { file ->
            WIP_ISSUE_ID.findAll(file.readText())
                .map { "${file.name}: ${it.value}" }
                .toList()
        }

        assertTrue(
            "ADRが _wip/ の項目IDを参照しています。_wip/ は廃棄されるので、" +
                "番号ではなく内容そのものを書いてください:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    private fun adrFiles(): List<File> =
        decisionsRoot().listFiles { f -> f.isFile && f.name.startsWith("ADR-") }
            ?.sorted()
            ?.also { require(it.isNotEmpty()) { "ADRが1本も見つかりません" } }
            ?: error("docs/dev/decisions を読めません")

    private fun decisionsRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        return listOf(
            workingDirectory.resolve("../docs/dev/decisions"),
            workingDirectory.resolve("docs/dev/decisions")
        ).firstOrNull { it.isDirectory }
            ?: error("docs/dev/decisions が見つかりません（作業ディレクトリ: $workingDirectory）")
    }

    private companion object {
        const val MAX_LINES = 30

        /**
         * `_wip/` の項目ID。英大文字のカテゴリ記号＋ハイフン＋数字の形。
         *
         * **ADR自身のID（`ADR-0001`）は除く** — ADR間の相互参照は恒久的で、
         * `_wip/` の廃棄では意味を失わない。
         */
        val WIP_ISSUE_ID = Regex("""\b(?!ADR-)[A-Z][A-Z0-9]*-\d+\b""")
    }
}
