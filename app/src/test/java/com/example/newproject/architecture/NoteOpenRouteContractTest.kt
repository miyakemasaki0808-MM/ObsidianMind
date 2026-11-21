package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **別のノートを開く経路が、先頭から開く境界（`openNoteFromTop`）を通っていること**を固定する。
 *
 * 本文の一覧の位置は Activity で共有され、ノートを替えても戻らない。境界を通らない経路で開くと、
 * 新しいノートが前のノートで読んでいた位置から始まる。**境界が何をするかは `NoteReadingFlowTest` が
 * 実際の一覧の位置で見る**ので、ここは配線だけを見る（`MainActivity` のルートは素のJVMから組み立てられない）。
 *
 * ノートタブの本文リンクと「このノートへ移る」は、始まりの位置を `rememberNoteStart` が置くので対象にしない。
 * 関連タブのバックナンバーは、見出しから始める対の境界（`openNoteAt`）を通る。どちらかを通っていれば始まりの位置が置かれる。
 * 冊子は `BookletRouteContractTest` が見る。
 *
 * 正本は [features/rediscover.md](../../../../../../../../docs/dev/features/rediscover.md) §5。
 */
class NoteOpenRouteContractTest {

    @Test
    fun `ノートタブの別のノートをひらくは先頭から開く`() {
        assertAllThroughBoundary(route("note"), listOf("viewModel.loadRandomNote("))
    }

    @Test
    fun `さがすと関連と結晶から開くノートは先頭から開く`() {
        assertAllThroughBoundary(route("search"), listOf("viewModel.openNote("))
        assertAllThroughBoundary(route("related"), listOf("viewModel.openNote("))
        assertAllThroughBoundary(route("crystal_list"), listOf("viewModel.openCrystalSource("))
    }

    /** Vault を選ぶ画面から戻ったときに引くノートも、本文の位置の口と同じスコープで境界を通す。 */
    @Test
    fun `Vault を選び直した直後に引くノートは先頭から開く`() {
        assertAllThroughBoundary(vaultPickerResult(), listOf("viewModel.loadRandomNote("))
    }

    /** ノートを開く呼び出しが1つ以上あり、**どれも境界（先頭から・置いた始まりから）と同じ行にある**こと。 */
    private fun assertAllThroughBoundary(route: Pair<String, String>, calls: List<String>) {
        val (name, body) = route
        val lines = body.lines().filter { line -> calls.any { it in line } }
        assertTrue("$name ルートにノートを開く呼び出しが見つかりません", lines.isNotEmpty())
        val bare = lines.filterNot { "openNoteFromTop(" in it || "openNoteAt(" in it }
        assertTrue(
            "$name ルートが openNoteFromTop・openNoteAt を通さずにノートを開いています:\n${bare.joinToString("\n") { it.trim() }}",
            bare.isEmpty()
        )
    }

    /**
     * `composable("name")` の中身。**次の `composable(` までを範囲とし、コメントは落とす**
     * （→ BookletRouteContractTest の `bookletRoute`）。
     */
    private fun route(name: String): Pair<String, String> {
        val source = mainActivity().readText()
        val start = source.indexOf("""composable("$name")""")
        check(start >= 0) { "MainActivity に $name ルートがありません" }
        val end = source.indexOf("composable(", start + 1).let { if (it < 0) source.length else it }
        return name to LINE_COMMENT.replace(source.substring(start, end), " ")
    }

    /** `OpenDocumentTree()` の結果を受けるラムダ（`{ uri ->` から対応する `}` まで）。コメントは落とす。 */
    private fun vaultPickerResult(): Pair<String, String> {
        val source = LINE_COMMENT.replace(mainActivity().readText(), " ")
        val contract = source.indexOf("OpenDocumentTree()")
        check(contract >= 0) { "MainActivity に Vault を選ぶ画面の呼び出しがありません" }
        val open = source.indexOf("{ uri ->", contract)
        check(open >= 0) { "Vault を選ぶ画面の結果を受けるラムダが見つかりません" }
        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) break
            }
            index++
        }
        return "Vault の選択" to source.substring(open, index + 1)
    }

    private fun mainActivity(): File =
        repositoryRoot().resolve("app/src/main/java/com/example/newproject/MainActivity.kt").also {
            assertTrue("MainActivity.kt がありません", it.isFile)
        }

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
        val LINE_COMMENT = Regex("""//[^\n]*""")
    }
}
