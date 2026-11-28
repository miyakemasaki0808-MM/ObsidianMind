package com.example.newproject.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 画面に出しうる文の書き方（→ `docs/dev/system/ui_copy.md` §2・§6）。
 * 製品のソースの文字列に、内部の数字・状態の言い直し・指示の口調を書かない。**意味は各面の契約テストが見る** —
 * ここは規則から外れた言い回しが戻ってくるのを落とすだけ。
 */
class UiCopyScanTest {

    private val literal = Regex("\"([^\"\\\\]|\\\\.)*\"")

    private val sources: List<File> = File("src/main/java/com/example/newproject")
        .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** 規則から外れた言い回しを含む文字列を、ファイル名と行で返す。 */
    private fun literalsContaining(words: List<String>): List<String> = sources.flatMap { file ->
        file.readLines().flatMapIndexed { i, line ->
            literal.findAll(line).map { it.value }
                .filter { text -> words.any { text.contains(it) } }
                .map { "${file.name}:${i + 1} $it" }
                .toList()
        }
    }

    @Test
    fun `内部の数字と文字コードの名前を画面の文にしない`() {
        val found = literalsContaining(listOf("40本", "ここに3件", "256KB", "UTF-8として", "先頭1MB", "1日に多くて"))
        assertTrue("内部の数字が残っている: $found", found.isEmpty())
    }

    @Test
    fun `スイッチや選択が見せている状態を文で言い直さない`() {
        val found = literalsContaining(listOf("表示しています", "選択済み"))
        assertTrue("状態の言い直しが残っている: $found", found.isEmpty())
    }

    @Test
    fun `指示の口調を使わない`() {
        val found = literalsContaining(listOf("ください"))
        assertTrue("指示の口調が残っている: $found", found.isEmpty())
    }
}
