package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **本文を書き換えた後は走査キャッシュを失効させるだけで、リンクの索引は捨てない**（→ `docs/dev/features/note_links.md` §6）。
 * 捨てると、蒸留の保存や太字の復元の後、次の走査が起きるまで別のノートへのリンクを開けない。
 *
 * 窓口は `ContentResolver` を要するので素のJVMで組み立てられず、どちらを呼ぶかはここでソースから見る。
 * 失効と破棄それぞれの中身は `NoteScanCacheTest` が確かめる。
 */
class NoteScanExpiryWiringTest {

    @Test
    fun `本文の読み直しは走査キャッシュを失効させ、Vaultの破棄は呼ばない`() {
        val body = functionBody(source(), "reloadNoteBody")
        assertTrue("reloadNoteBody が走査キャッシュを失効させていない", "noteScan.expireAfterBodyWrite()" in body)
        assertFalse("reloadNoteBody がリンクの索引まで捨てている", "noteScan.clear()" in body)
    }

    @Test
    fun `Vaultの切替は走査キャッシュをリンクの索引ごと捨てる`() {
        assertTrue("saveVault が走査キャッシュを捨てていない", "noteScan.clear()" in functionBody(source(), "saveVault"))
    }

    /** `fun name(` から、対応する閉じ括弧までの本体。 */
    private fun functionBody(text: String, name: String): String {
        val start = Regex("""fun\s+$name\s*\(""").find(text)?.range?.first
            ?: error("$name が見つかりません")
        val open = text.indexOf('{', start)
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, index + 1)
            }
        }
        error("$name の本体が閉じていません")
    }

    private fun source(): String {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val root = listOf(workingDirectory.resolve("src"), workingDirectory.resolve("app/src"))
            .firstOrNull { it.isDirectory }
            ?: error("app/src が見つかりません（作業ディレクトリ: $workingDirectory）")
        return root.resolve("main/java/com/example/newproject/NoteViewModel.kt").readText()
    }
}
