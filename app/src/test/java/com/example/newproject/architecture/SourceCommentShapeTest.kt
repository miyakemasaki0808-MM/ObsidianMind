package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **コメントの形のうち、機械で確かめられる部分を固定する。**
 *
 * コメントの中身の良し悪しは読んで判断するしかないが、
 * **どの宣言にも付いていないコメント**は形だけで見つかる。
 */
class SourceCommentShapeTest {

    /**
     * **KDoc を2つ続けて置かない。**
     *
     * Kotlin は宣言の直前にある KDoc を1つだけ付ける。2つ続くと前の1つはどの宣言にも付かず、
     * IDE で宣言を指しても表示されない。**書いた本人には付いているように見える**ので、読み返しでは気づけない。
     *
     * 起きるのは、長い KDoc と宣言のあいだに後から別の宣言と KDoc を挿し込んだとき。
     * 見つかった時点で本番に9箇所・テストに6箇所あり、クラス本体の説明が
     * 途中に足された別の型の上に取り残されていた（`NoteUiStateStore`）。
     *
     * 続けて置きたくなったら1つにまとめるか、本来の宣言の直前へ移す。
     */
    @Test
    fun `KDocは2つ続けて置かない`() {
        val violations = kotlinSources()
            .flatMap { file ->
                val text = file.readText()
                KDOC.findAll(text)
                    .zipWithNext()
                    .filter { (first, second) -> text.substring(first.range.last + 1, second.range.first).isBlank() }
                    .map { (first, _) -> "${file.relativeTo(sourceSetsRoot())}:${lineOf(text, first.range.first)}" }
            }
            .sorted()
            .toList()

        assertTrue(
            "どの宣言にも付いていない KDoc があります（直後にもう1つ KDoc が続いている）:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    private fun lineOf(text: String, offset: Int): Int = text.substring(0, offset).count { it == '\n' } + 1

    /** 付け忘れの起き方はソースセットで変わらないので、3つとも同じ規則で数える。 */
    private fun kotlinSources(): Sequence<File> =
        SOURCE_SETS.asSequence()
            .map { sourceSetsRoot().resolve("$it/java/com/example/newproject") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }

    private fun sourceSetsRoot(): File = repositoryRoot().resolve("app/src")

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
        val SOURCE_SETS = listOf("main", "test", "androidTest")

        val KDOC = Regex("""/\*\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    }
}
