package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「最後まで読んだ」を到達率の比較で直に書かないことを固定する。
 *
 * 再会カードと前回の読書の跡は同じ判定を使う（→ features/margin_pane.md §5.7）。判定は `isReadToEnd` の1つで、
 * 比較を各所に書くと、片方だけを変えたときに「カードは読了と言うのに余白には前回の跡が出る」ように食い違う。
 */
class ReadToEndJudgmentTest {

    @Test
    fun `到達率を100と直に比べるのは isReadToEnd だけ`() {
        val sites = mainSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { DIRECT_COMPARISON.containsMatchIn(it.readText()) }
            .map { it.relativeTo(mainSourceRoot()).invariantSeparatorsPath }
            .toSet()

        assertEquals(setOf(DEFINITION), sites)
    }

    @Test
    fun `検査の式は直の比較を捕まえる`() {
        listOf(
            "card.lastProgressPercent >= 100",
            "visit.progressPercent < 100",
            "progressPercent==100"
        ).forEach { line ->
            assertTrue(line, DIRECT_COMPARISON.containsMatchIn(line))
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

    private companion object {
        const val DEFINITION = "model/ReadingTrace.kt"
        val DIRECT_COMPARISON = Regex("""[Pp]rogressPercent\s*(>=|<=|==|!=|<|>)\s*100\b""")
    }
}
