package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Markdown解析を伴う抜粋生成が、Main上の呼び出し元へ再び直書きされないことを固定する。
 */
class NoteExcerptThreadingTest {

    @Test
    fun `本番の7経路はDefaultディスパッチャへ切り替えてから抜粋を作る`() {
        val sourceRoot = mainSourceRoot()
        val callCounts = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "NoteExcerptBuilder.kt" }
            .mapNotNull { file ->
                val count = BUILD_CALL.findAll(file.readText()).count()
                file.relativePath().takeIf { count > 0 }?.let { it to count }
            }
            .toMap()

        assertEquals(EXPECTED_CALL_COUNTS, callCounts)

        EXPECTED_CALL_COUNTS.forEach { (relativePath, expectedCount) ->
            val source = sourceRoot.resolve(relativePath).readText()
            assertEquals(
                "$relativePath の抜粋生成が excerptDispatcher の外へ出ています",
                expectedCount,
                OFF_MAIN_BUILD_CALL.findAll(source).count()
            )
            assertEquals(
                "$relativePath の本番既定ディスパッチャが Default ではありません",
                1,
                DEFAULT_DISPATCHER.findAll(source).count()
            )
        }
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
        private val EXPECTED_CALL_COUNTS = mapOf(
            "domain/SummarizeUseCase.kt" to 1,
            "domain/RelatedNotesUseCase.kt" to 1,
            "controller/RemarkController.kt" to 2,
            "controller/QuizController.kt" to 1,
            "controller/SectionChatController.kt" to 3
        )
        private val BUILD_CALL = Regex("""\bbuildNoteExcerpt\(""")
        private val OFF_MAIN_BUILD_CALL =
            Regex("""withContext\(excerptDispatcher\)\s*\{\s*buildNoteExcerpt\(""")
        private val DEFAULT_DISPATCHER =
            Regex("""excerptDispatcher:\s*CoroutineDispatcher\s*=\s*Dispatchers\.Default""")
    }
}
