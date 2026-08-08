package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * instrumentation テストの**形**をCIで固定する。
 *
 * ## なぜ要るか
 *
 * JUnit4 は `@Test` メソッドの戻り値が `void` であることを要求する。
 * Kotlin で `fun x() = runBlocking { ... }` と書くと**ブロックの最後の式の型が
 * そのまま戻り値になる**ため、末尾が値を返す呼び出し（`Log.i()` は `Int` を返す）
 * だと `void` でなくなる。
 *
 * **コンパイルは通る。** `assembleDebugAndroidTest` は JUnit の契約を見ないので、
 * 壊れていることは実機で走らせるまで分からない。しかも
 * **`InvalidTestClassError` はクラス単位**なので、1メソッドの型ミスで
 * **そのクラスのテストが全件起動しない**（2026-08-08 に4件が丸ごと止まった）。
 *
 * 「末尾に値を返す式を置かない」は人が守る規約にできない — 末尾に1行足しただけで
 * 破れ、破れたことが緑/赤ではなく**件数の減少**として現れるため気づけない。
 * そこで**書き方のほうを縛る**: `runBlocking` を式本体に使うなら `runBlocking<Unit>` にする。
 *
 * ## この検査が見ていないもの
 *
 * 式本体が `runBlocking` **以外**（`runTest` など）の場合は対象外。
 * 使い始めたらここへ足す。ブロック本体（`fun x() { ... }`）は戻り値が `Unit` に
 * 決まるので元から安全。
 */
class InstrumentationTestShapeTest {

    @Test
    fun `式本体の runBlocking は Unit を明示している`() {
        val violations = instrumentationSources().flatMap { file ->
            UNTYPED_EXPRESSION_BODY.findAll(file.readText()).map { match ->
                "${file.name}: ${match.groupValues[1].trim()} — " +
                    "`runBlocking {` ではなく `runBlocking<Unit> {` にすること"
            }
        }.sorted()

        assertTrue(
            "@Test の戻り値が void でなくなると、そのクラスのテストが全件起動しません:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    private fun instrumentationSources(): List<File> =
        androidTestRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun androidTestRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        val candidates = listOf(
            workingDirectory.resolve("src/androidTest"),
            workingDirectory.resolve("app/src/androidTest")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("app/src/androidTest が見つかりません（作業ディレクトリ: $workingDirectory）")
    }

    private companion object {
        /** `fun なにか() = runBlocking {`（型引数なし）を拾う。 */
        val UNTYPED_EXPRESSION_BODY =
            Regex("""^\s*(fun\s+[^\n]*?\(\))\s*=\s*runBlocking\s*\{""", RegexOption.MULTILINE)
    }
}
