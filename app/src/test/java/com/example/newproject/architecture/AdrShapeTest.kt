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
     * **正規表現でIDらしき形を弾かない。** それをやると `UTF-8`・`SHA-256` や、
     * 設計書が内部で使う段階番号まで巻き込む。
     * **`_wip/` の見出しから実在するIDだけを収穫し、その集合だけを禁じる。**
     * 収穫元が消えれば検査も自然に消えるので、`_wip/` の廃棄で壊れない。
     */
    @Test
    fun `恒久文書は _wip の項目IDを参照しない`() {
        val ids = wipIssueIds()
        require(ids.isNotEmpty()) { "_wip/ から項目IDを1つも収穫できませんでした" }

        val violations = permanentDocs().flatMap { file ->
            val text = file.readText()
            ids.filter { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(text) }
                .map { "${file.parentFile?.name}/${file.name}: $it" }
        }

        assertTrue(
            "恒久文書が _wip/ の項目IDを参照しています。_wip/ は廃棄されるので、" +
                "番号ではなく内容そのものを書いてください:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    /** `_wip/` の見出しに実在する項目ID。カテゴリ記号つき見出しとドット形式の両方を拾う。 */
    private fun wipIssueIds(): Set<String> =
        wipRoot().listFiles { f -> f.isFile && f.extension == "md" }.orEmpty()
            .flatMap { file ->
                val text = file.readText()
                WIP_HEADING_ID.findAll(text).map { it.groupValues[1] } +
                    WIP_HEADING_ID_DOTTED.findAll(text).map { it.groupValues[1] }
            }
            .toSet()

    private fun permanentDocs(): List<File> =
        listOf("features", "system", "decisions")
            .map { devRoot().resolve(it) }
            .filter { it.isDirectory }
            .flatMap { it.listFiles { f -> f.isFile && f.extension == "md" }.orEmpty().toList() }

    private fun wipRoot(): File = docsRoot().resolve("_wip")

    private fun devRoot(): File = docsRoot().resolve("dev")

    private fun docsRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        return listOf(workingDirectory.resolve("../docs"), workingDirectory.resolve("docs"))
            .firstOrNull { it.isDirectory }
            ?: error("docs が見つかりません（作業ディレクトリ: $workingDirectory）")
    }

    private fun adrFiles(): List<File> =
        decisionsRoot().listFiles { f -> f.isFile && f.name.startsWith("ADR-") }
            ?.sorted()
            ?.also { require(it.isNotEmpty()) { "ADRが1本も見つかりません" } }
            ?: error("docs/dev/decisions を読めません")

    private fun decisionsRoot(): File = devRoot().resolve("decisions")

    private companion object {
        const val MAX_LINES = 30

        /** `## ABC-9 …` の形（カテゴリ記号つき見出し）。 */
        val WIP_HEADING_ID = Regex("""^#+\s+\*{0,2}([A-Z][A-Z0-9]*-\d+)""", RegexOption.MULTILINE)

        /** `#### Z-9. …` の形（1文字カテゴリ＋ドット。`feature_ideas.md`）。 */
        val WIP_HEADING_ID_DOTTED = Regex("""^#+\s+([A-Z]-\d+)\.""", RegexOption.MULTILINE)
    }
}
