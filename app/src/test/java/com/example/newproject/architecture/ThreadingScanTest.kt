package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **入力サイズに比例する純関数を、Main のスコープから呼ばない**ことをソース走査で固定する
 * （→ docs/dev/lessons.md L13・docs/dev/system/architecture.md 判断3）。
 *
 * ## 足し方
 *
 * 重い純関数を Main の外へ逃がしたら、[HEAVY_CALLS] へ1行足す。**ファイルは増やさない。**
 * どの行にも同じ3つを当てる。
 *
 * - **呼んでよいファイルの外から呼んだら落ちる。** 新しく呼ぶなら、Main の外へ逃がしたかを確かめてから表へ足す
 * - **回数が表と違えば落ちる。0回でも落ちる** — 呼び出しの形が変わって走査が空振りすると、
 *   「全部逃がしている」が0対0で通ってしまう
 * - **呼ぶたびに `withContext(ディスパッチャ) { … }` の内側でなければ落ちる。** 内側かどうかは括弧の対応で見る —
 *   文字数の窓で探すと、手前の別の `withContext` から届いてしまう。ディスパッチャの本番既定も `Dispatchers.Default` であること
 *
 * ## 見ていないもの
 *
 * - **登録漏れ。** 表に無い重い関数は見ない。閉じるには「重い」を型か注釈で表明し、呼び出し元を機械で見る形が要る
 * - **実際に別スレッドで走るか。** 走査が見るのは呼び出しの形までで、結果は各 Controller のテストが見る（→ docs/dev/lessons/L55.md）
 */
class ThreadingScanTest {

    /**
     * @property call 呼び出しの正規表現。定義（`fun 名前(`）は数えない
     * @property offMain 逃がして呼ぶファイルと回数
     * @property nested 別の行の重い関数の中からだけ呼ばれるファイルと、その理由。ディスパッチャは見ない
     */
    private data class HeavyCall(
        val name: String,
        val call: String,
        val dispatcher: String,
        val offMain: Map<String, Int>,
        val nested: Map<String, String> = emptyMap()
    )

    @Test
    fun `重い純関数はディスパッチャの内側でだけ呼ぶ`() {
        val violations = HEAVY_CALLS.flatMap { heavy ->
            heavy.offMain.mapNotNull { (path, expected) ->
                val source = mainSource(path)
                val calls = heavy.callRegex().findAll(source).map { it.range.first }.toList()
                val blocks = blockRanges(source, Regex("""withContext\(\s*${heavy.dispatcher}\s*\)\s*\{"""))
                val inside = calls.count { call -> blocks.any { call in it } }
                "${heavy.name}: $path は ${calls.size} 回呼び、${heavy.dispatcher} の内側は $inside 回（表は $expected 回）"
                    .takeIf { calls.size != expected || inside != expected }
            }
        }

        assertTrue("重い純関数が Main の外へ逃がされていません:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun `重い純関数を呼ぶファイルは表にあるものだけ`() {
        val files = mainSourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        HEAVY_CALLS.forEach { heavy ->
            val callers = files
                .filter { heavy.callRegex().containsMatchIn(stripComments(it.readText())) }
                .map { it.relativePath() }
                .toSet()
            assertEquals(
                "${heavy.name} を呼ぶファイルが表と違います。新しく呼ぶなら、Main の外へ逃がしてから表へ足してください",
                heavy.offMain.keys + heavy.nested.keys,
                callers
            )
        }
    }

    @Test
    fun `逃がす先の本番既定は Default である`() {
        val violations = HEAVY_CALLS.flatMap { heavy ->
            heavy.offMain.keys.mapNotNull { path ->
                val defaults = Regex("""${heavy.dispatcher}:\s*CoroutineDispatcher\s*=\s*Dispatchers\.Default""")
                    .findAll(mainSource(path)).count()
                "$path の ${heavy.dispatcher} の本番既定が Default ではありません（$defaults 箇所）".takeIf { defaults != 1 }
            }
        }

        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    /**
     * **逃がしたら、元の同期経路が代わりに走らないかを対で見る**（→ L13）。
     * `MarkdownRenderer` は短い本文をその場で描く用途のために `precomputedBlocks ?: parseMarkdownBlocks(content)` を持つ。
     * ノート本文（最大1MB）がそこへ落ちると Main で解析し直すので、本文パネルは解析済みブロックが届くまで本文を描かない。
     */
    @Test
    fun `本文パネルはノート本文を解析のフォールバックへ落とさない`() {
        assertTrue(
            "$NOTE_COMPONENTS がノート本文を解析結果の到着前に描いています",
            Regex("""if\s*\(!isNote\s*\|\|\s*blocksForContent\s*!=\s*null\)""").containsMatchIn(mainSource(NOTE_COMPONENTS))
        )
    }

    /**
     * **中止は要求であって完了ではない**（→ docs/dev/lessons/L49.md）。
     *
     * `cancel()` は本番では Main から呼ばれ、書き手は `ioDispatcher`（別スレッド）で走る。
     * その場で途中経過を確定すると、まだ書いている分だけ少なく報告する。書き手を `join()` してから数える。
     * **この交錯は単一スレッドのテストスケジューラでは作れない**ので、順序は走査で固定し、
     * 件数の一致そのものは `ReadingTraceBackupControllerTest` が見る。
     */
    @Test
    fun `退避の中止の結果は書き手を join してから確定する`() {
        val source = mainSource(BACKUP_CONTROLLER)

        val interrupted = Regex("""interrupted = true""").findAll(source).count()
        assertTrue("中止の結果を作る箇所が見つかりません", interrupted > 0)
        assertEquals(
            "書き手の停止を待たずに中止の結果を確定しています",
            interrupted,
            Regex("""\bjoin\(\)[\s\S]{0,600}?interrupted = true""").findAll(source).count()
        )
    }

    private fun HeavyCall.callRegex(): Regex = Regex("""(?<!fun )$call""")

    /** `{` の直後から、対応する `}` の手前まで。文字列やコメントの中の括弧は数えない前提（コメントは先に落とす）。 */
    private fun blockRanges(source: String, opener: Regex): List<IntRange> = opener.findAll(source).map { match ->
        var depth = 1
        var index = match.range.last + 1
        while (index < source.length && depth > 0) {
            when (source[index]) {
                '{' -> depth++
                '}' -> depth--
            }
            index++
        }
        match.range.last + 1 until index
    }.toList()

    private fun mainSource(path: String): String {
        val file = mainSourceRoot().resolve(path)
        require(file.isFile) { "走査対象が見つかりません: $path" }
        return stripComments(file.readText())
    }

    /** KDoc に書いた呼び出しの形を数えないよう、ブロックコメントと行頭からのコメントを落とす。 */
    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("""(?m)^\s*//[^\n]*"""), " ")

    private fun mainSourceRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        val candidates = listOf(
            workingDirectory.resolve("src/main/java/com/example/newproject"),
            workingDirectory.resolve("app/src/main/java/com/example/newproject")
        )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("main source root が見つかりません: $workingDirectory")
    }

    private fun File.relativePath(): String =
        invariantSeparatorsPath.substringAfter("/src/main/java/com/example/newproject/")

    private companion object {
        const val BACKUP_CONTROLLER = "controller/ReadingTraceBackupController.kt"
        const val NOTE_COMPONENTS = "ui/component/NoteComponents.kt"

        val HEAVY_CALLS = listOf(
            HeavyCall(
                name = "AI入力の抜粋",
                call = """\bbuildNoteExcerpt\(""",
                dispatcher = "excerptDispatcher",
                offMain = mapOf(
                    "domain/SummarizeUseCase.kt" to 1,
                    "domain/RelatedNotesUseCase.kt" to 1,
                    "controller/NoteFieldController.kt" to 1,
                    "controller/SectionChatController.kt" to 1,
                    "controller/RebuttalController.kt" to 1
                )
            ),
            HeavyCall(
                name = "節のモデル",
                call = """\bbuildNoteSectionModel\(""",
                dispatcher = "parseDispatcher",
                offMain = mapOf("controller/NoteSectionController.kt" to 1)
            ),
            HeavyCall(
                name = "本文のブロック解析",
                call = """\bparseMarkdownBlocks\(""",
                dispatcher = "parseDispatcher",
                offMain = mapOf("controller/SideReadingController.kt" to 1),
                nested = mapOf(
                    "domain/markdown/NoteSections.kt" to "節のモデルの中",
                    "domain/NoteExcerptBuilder.kt" to "AI入力の抜粋の中",
                    "domain/RebuttalClaimScanner.kt" to "反証の候補の列挙の中",
                    "ui/markdown/MarkdownRenderer.kt" to "短い本文をその場で描くフォールバック（→ 本文パネルの検査）"
                )
            ),
            HeavyCall(
                name = "反証の候補の列挙",
                call = """\bscanRebuttalClaims\(""",
                dispatcher = "excerptDispatcher",
                offMain = mapOf("controller/RebuttalController.kt" to 1)
            ),
            HeavyCall(
                name = "退避ファイルの組み立てと解析",
                call = """\bReadingTraceBackupJson\.(?:encode|decode)\(""",
                dispatcher = "cpuDispatcher",
                offMain = mapOf(BACKUP_CONTROLLER to 2)
            ),
            HeavyCall(
                name = "退避ファイル内の重複の畳み込み",
                call = """\bfoldDuplicates\(""",
                dispatcher = "cpuDispatcher",
                offMain = mapOf(BACKUP_CONTROLLER to 1)
            )
        )
    }
}
