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
                KotlinCommentScanner.detachedKDocOffsets(text)
                    .map { offset -> "${file.relativeTo(sourceSetsRoot())}:${KotlinCommentScanner.lineOf(text, offset)}" }
            }
            .sorted()
            .toList()

        assertTrue(
            "どの宣言にも付いていない KDoc があります（直後にもう1つ KDoc が続いている）:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    /**
     * **本番コードのコメントに経緯を書かない。** 日付とレビューの指摘番号を目印に落とす。
     *
     * コメントに残すのは「今のコードを変える人が知らないと壊すこと」で、
     * **いつ・どのレビューで・何度直したかはコミットメッセージが持つ**（CLAUDE.md「必須原則」）。
     * 経緯をコメントへ書くと、同じ事件が設計書・教訓・コミットと並んで4つ目の写しになり、
     * 読む量だけが増えて、どれかが必ず古くなる。
     *
     * **レビューの指摘番号は参照にもならない。** レビュー本文はコミットせずに消すので、
     * 書いた時点から参照先が存在しない。出どころを残すなら番号ではなく内容を書く。
     *
     * **見るのは本番コードだけ。** テストは「どの変異が素通りしたか」を固定する場所で、
     * 経緯そのものが検査の根拠になっていることがある。
     *
     * 引用された書式の例と、文字列リテラルの中は数えない（→ [KotlinCommentScanner]）。
     */
    @Test
    fun `本番コードのコメントに日付とレビューの指摘番号を書かない`() {
        val violations = kotlinSources(listOf("main"))
            .flatMap { file ->
                KotlinCommentScanner.historyMarkLines(file.readText())
                    .map { (line, content) -> "${file.relativeTo(sourceSetsRoot())}:$line: $content" }
            }
            .sorted()
            .toList()

        assertTrue(
            "コメントに経緯（日付・レビューの指摘番号）があります。今も効く制約だけを現在形で残し、" +
                "経緯はコミットメッセージへ:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    /** 付け忘れの起き方はソースセットで変わらないので、既定では3つとも同じ規則で数える。 */
    private fun kotlinSources(sourceSets: List<String> = SOURCE_SETS): Sequence<File> =
        sourceSets.asSequence()
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
    }
}
