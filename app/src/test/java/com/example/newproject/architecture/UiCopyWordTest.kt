package com.example.newproject.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ボタンの語の組（→ `docs/dev/system/ui_copy.md` §3）。**重さで使い分ける** — 軽い操作は「やめる・消す・止める」、
 * 取り消せない操作の確認は「キャンセル・削除」。確認の外で重い組を使う直書きと、中断の「中止」を落とす。
 */
class UiCopyWordTest {

    private val uiFiles: List<File> = File("src/main/java/com/example/newproject/ui")
        .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** ボタンの文字として書いた [word] の行（`Text("語"` か `Text(text = "語"`）。 */
    private fun buttonLines(word: String): List<Pair<File, Int>> = uiFiles.flatMap { file ->
        file.readLines().mapIndexedNotNull { i, line ->
            if (line.contains("Text(\"$word\"") || line.contains("Text(text = \"$word\"")) file to i else null
        }
    }

    /** [file] の [index] 行が、確認のダイアログの [slot]（`confirmButton` か `dismissButton`）の中にあるか。 */
    private fun insideDialogSlot(file: File, index: Int, slot: String): Boolean {
        val lines = file.readLines()
        val window = lines.subList((index - 4).coerceAtLeast(0), index + 1)
        return window.any { it.contains("$slot = {") }
    }

    @Test
    fun `取り消しのキャンセルは取り消せない操作の確認でだけ使う`() {
        buttonLines("キャンセル").forEach { (file, i) ->
            assertTrue("${file.name}:${i + 1} の「キャンセル」が確認のダイアログの外にある（軽い操作は「やめる」）", insideDialogSlot(file, i, "dismissButton"))
        }
    }

    @Test
    fun `削除は取り消せない操作の確認でだけ使う`() {
        buttonLines("削除").forEach { (file, i) ->
            assertTrue("${file.name}:${i + 1} の「削除」が確認のダイアログの外にある（軽い操作は「消す」）", insideDialogSlot(file, i, "confirmButton"))
        }
    }

    @Test
    fun `確認のダイアログの実行に消すを使わない`() {
        buttonLines("消す").forEach { (file, i) ->
            assertTrue("${file.name}:${i + 1} の「消す」が確認のダイアログの実行にある（取り消せない操作は「削除」）", !insideDialogSlot(file, i, "confirmButton"))
        }
    }

    @Test
    fun `中断は止めるで言い、中止を使わない`() {
        val found = uiFiles.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line -> if (line.contains("中止\"")) "${file.name}:${i + 1}" else null }
        }
        assertTrue("中断のボタンに「中止」がある: $found", found.isEmpty())
    }
}
