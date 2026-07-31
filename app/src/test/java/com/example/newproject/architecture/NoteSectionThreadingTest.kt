package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表示用Markdownの解析が Composable（＝Main）へ再び直書きされないことを固定する。
 *
 * [com.example.newproject.architecture.NoteExcerptThreadingTest] のAI入力版と対になるもので、
 * 守っている性質も同じ「入力サイズに比例する純関数を Main のスコープから呼ばない」。
 * かつて `NoteReaderTab` と `FullscreenNoteScreen` がそれぞれの `remember` で
 * 同期実行しており、最大1MBの本文を開くたび・全画面へ入るたびにUIが止まっていた。
 */
class NoteSectionThreadingTest {

    @Test
    fun `セクションモデルの構築は NoteSectionController だけが行う`() {
        val callSites = mainSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { BUILD_CALL.containsMatchIn(it.readText()) }
            .map { it.relativePath() }
            .toSet()

        assertEquals(setOf(SECTION_CONTROLLER, SECTIONS_DEFINITION), callSites)
    }

    @Test
    fun `NoteSectionController は parseDispatcher の外で構築しない`() {
        val source = mainSourceRoot().resolve(SECTION_CONTROLLER).readText()

        assertEquals(
            "$SECTION_CONTROLLER の構築が parseDispatcher の外へ出ています",
            BUILD_CALL.findAll(source).count(),
            OFF_MAIN_BUILD_CALL.findAll(source).count()
        )
        assertEquals(
            "$SECTION_CONTROLLER の本番既定ディスパッチャが Default ではありません",
            1,
            DEFAULT_DISPATCHER.findAll(source).count()
        )
    }

    @Test
    fun `ui パッケージはブロック解析を自前で呼ばない`() {
        // MarkdownRenderer だけは、AI補記の結果画面など「短い本文をその場で描く」
        // 用途のフォールバックとして parseMarkdownBlocks を持つ。ノート本文（最大1MB）は
        // フォールバックへ落とさない — 落ちた瞬間に Main で解析し直すため、
        // NoteContentPanel が解析済みブロックの到着まで本文を描かないことで塞いでいる。
        val offenders = mainSourceRoot().resolve("ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { PARSE_CALL.containsMatchIn(it.readText()) }
            .map { it.relativePath() }
            .toSet()

        assertEquals(setOf(MARKDOWN_RENDERER), offenders)

        val panel = mainSourceRoot().resolve(NOTE_COMPONENTS).readText()
        assertTrue(
            "$NOTE_COMPONENTS がノート本文を解析結果の到着前に描いています",
            BODY_GUARD.containsMatchIn(panel)
        )
    }

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

    companion object {
        private const val SECTION_CONTROLLER = "controller/NoteSectionController.kt"
        private const val SECTIONS_DEFINITION = "domain/markdown/NoteSections.kt"
        private const val MARKDOWN_RENDERER = "ui/markdown/MarkdownRenderer.kt"
        private const val NOTE_COMPONENTS = "ui/component/NoteComponents.kt"

        private val BUILD_CALL = Regex("""\bbuildNoteSectionModel\(""")
        private val OFF_MAIN_BUILD_CALL =
            Regex("""withContext\(parseDispatcher\)\s*\{\s*buildNoteSectionModel\(""")
        private val DEFAULT_DISPATCHER =
            Regex("""parseDispatcher:\s*CoroutineDispatcher\s*=\s*Dispatchers\.Default""")
        private val PARSE_CALL = Regex("""\bparseMarkdownBlocks\(""")
        private val BODY_GUARD = Regex("""if\s*\(!isNote\s*\|\|\s*blocksForContent\s*!=\s*null\)""")
    }
}
