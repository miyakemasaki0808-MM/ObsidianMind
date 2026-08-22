package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **文書が名指しする「現行スキーマ版」が、コードの定数と一致していることを固定する。**
 *
 * ## なぜ要るか
 *
 * v5 → v6 へ上げたとき、実装とデータ表は直したのに、**6ファイル9箇所が v5 のまま残った**。
 * 外部レビューが見つけたのは2箇所で、残り7箇所は grep して初めて出た。
 * 版番号は「1つの事実」なのに**置き場所だけが散っている**という、最も取り残しやすい形である
 * （→ [lessons L14](../../../../../../../../docs/dev/lessons.md)）。
 *
 * 次に版を上げるのは退避・復元（X-9）なので、**そこで同じことが起きると
 * 「現行スキーマ」と検証基準を取り違える。**
 *
 * ## 見ているもの（2つ）
 *
 * 1. **現行版に触れる義務がある文書**（[DOCS_STATING_CURRENT_VERSION]）が、現行の版番号を含むこと。
 *    版を上げて文書を丸ごと忘れる、という**実際に起きた形**をここで捕まえる。
 * 2. 「現行は〜」と**明示的に主張している**版番号が、コードの定数と一致すること。
 *
 * ## 見ていないもの
 *
 * **履歴としての言及は対象外。**「判断9（schema v2）」「`reflection`（schema v4〜）」
 * 「v1〜v5 も読める」はいずれも正しい記述で、落とすと版を上げるたびに過去の説明を書き換えることになる。
 * **「現行」と書いていない版番号は、履歴か説明とみなす。**
 *
 * **`_wip/` と `review/` は対象外。** 課題や指摘の本文は
 * 「v5 のまま残っている」のように**古い版を意図して名指しする**ので、現行版の主張と区別が付かない。
 * どちらも捨てる文書なので、恒久文書側を固定すれば足りる。
 */
class SchemaVersionDocsTest {

    /**
     * **版を上げて文書を丸ごと忘れる形を捕まえる。** v5 → v6 のとき、
     * 6ファイル9箇所が取り残された（外部レビューが見つけたのは2箇所）。
     */
    @Test
    fun `現行版に触れる文書はすべて現行の版番号を含む`() {
        val current = currentSchemaVersion()
        val missing = DOCS_STATING_CURRENT_VERSION.filterNot { path ->
            val file = repositoryRoot().resolve("docs/$path")
            check(file.isFile) { "対象文書が見つかりません: $path" }
            file.readText().contains("v$current")
        }.sorted()

        assertTrue(
            "版を上げたら、現行版に触れている文書も全部直すこと（v$current が出てこない）:\n" +
                missing.joinToString("\n"),
            missing.isEmpty()
        )
    }

    /** 「現行は〜」と明示している版番号だけを、コードの定数と突き合わせる。 */
    @Test
    fun `現行版として名指しされた版番号はコードと一致する`() {
        val current = currentSchemaVersion()
        val violations = documentFiles().flatMap { file ->
            val text = file.readText()
            CURRENT_VERSION_CLAIMS.flatMap { pattern ->
                pattern.findAll(text)
                    .map { it.groupValues[1].toInt() to it.value }
                    .filter { (version, _) -> version != current }
                    .map { (_, claim) -> "${file.name}: 「$claim」が現行版（v$current）と食い違う" }
                    .toList()
            }
        }.sorted()

        assertTrue(
            "現行スキーマ版の記述がコードと食い違っています:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    private fun currentSchemaVersion(): Int {
        val source = repositoryRoot()
            .resolve("app/src/main/java/com/example/newproject/model/ReadingTrace.kt")
            .readText()
        return requireNotNull(SCHEMA_CONSTANT.find(source)) {
            "READING_TRACE_SCHEMA_VERSION の宣言が見つかりません。"
        }.groupValues[1].toInt()
    }

    /**
     * **判断の正本（`dev/`）だけを見る。**
     *
     * `_wip/`・`review/` は古い版を意図して名指しする（「v5 のまま残っている」）。
     * `owner/` は**オーナーが指示したときだけ更新する俯瞰**なので、検査に載せない
     * （→ `docs/owner/README.md`）。
     */
    private fun documentFiles(): List<File> =
        repositoryRoot().resolve("docs/dev").walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .toList()

    private fun repositoryRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        return sequenceOf(workingDirectory, workingDirectory.parentFile)
            .filterNotNull()
            .firstOrNull { it.resolve("docs/dev").isDirectory }
            ?: error("リポジトリルートが見つかりません: $workingDirectory")
    }

    private companion object {
        val SCHEMA_CONSTANT = Regex("""READING_TRACE_SCHEMA_VERSION\s*=\s*(\d+)""")

        /** 現行版に触れる義務がある文書。**版を上げたら、ここへ挙がっているものは全部直す。** */
        val DOCS_STATING_CURRENT_VERSION = listOf(
            "dev/features/reflect_reading_trace.md",
            "dev/features/reflect_remark.md",
            "dev/features/reading_trace_backup.md"
        )

        /**
         * 「これが現行版だ」と主張する言い回し。
         *
         * **「現行」と明示している形だけを載せる。** これ以外の版番号は履歴か説明とみなす。
         * 新しい言い回しで現行版を書くと素通りするので、**現行版に触れるときは
         * 既存の言い回しへ寄せるか、[DOCS_STATING_CURRENT_VERSION] へ文書を足すこと。**
         */
        val CURRENT_VERSION_CLAIMS = listOf(
            Regex("""現行 v(\d+)"""),
            Regex("""現行は schema v(\d+)"""),
            Regex("""現行 `schemaVersion = (\d+)`""")
        )
    }
}
