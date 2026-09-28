package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **結晶は、再会カードの照合より後に試す**（→ `docs/dev/features/reflect_crystal.md` 判断10）。
 *
 * 結晶は照合の Job が終わるのを待ってから生成の錠を取る。待つ時点で照合が**既に要求されている**
 * ことが前提で、それを決めているのは Rediscover の窓口の呼び出し順である。
 * 窓口は `ContentResolver` を要するので素のJVMで組み立てられず、順序はここでソースから見る。
 * 待つこと自体は `NoteSessionCoordinatorTest` が本番と同じ入口から確かめる。
 */
class CrystalWiringOrderTest {

    @Test
    fun `引いたノートでは照合を要求してから結晶を試す`() {
        val body = functionBody(source(), "presentDrawnNote")
        val reveal = body.indexOf("session.revealReadingTrace(")
        val crystallize = body.indexOf("session.crystallize(")
        assertTrue("presentDrawnNote が照合を要求していない", reveal >= 0)
        assertTrue("presentDrawnNote が結晶を試していない", crystallize >= 0)
        assertTrue("結晶を照合より先に試している（結晶が照合を待てない）", reveal < crystallize)
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
