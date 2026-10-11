package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 見出しの面を広げる幅と、画面の余白が同じ値から来ていることを固定する。
 *
 * `GradientHeader` は親の左右の余白の外まで面を広げるので、余白と広げる幅が食い違うと
 * **見出しの面だけが画面の端からずれる**。レイアウトは素のJVMで測れないので、ここでは
 * 「見出しを置く画面は `screenContentPadding()` で余白を取る」と「広げる幅と余白が同じ定数」の2つを見る。
 * 同じ形で、題名の行の高さと丸ボタンの大きさが同じ定数から来ていることも見る。
 *
 * **届かない形がある。** 見出しを余白の内側でさらに入れ子にして余白を足す形は、この走査では見えない。
 */
class GradientHeaderPaddingTest {

    @Test
    fun `見出しを置く画面は画面の余白を screenContentPadding で取る`() {
        val screens = mainSources().filter { (_, text) -> HEADER_CALL.containsMatchIn(text) }
        assertTrue("GradientHeader を呼ぶ画面が見つかりません", screens.isNotEmpty())

        val missing = screens.filterNot { (_, text) -> "screenContentPadding()" in text }.map { it.first }
        assertEquals("screenContentPadding() で余白を取っていない画面があります", emptyList<String>(), missing)
    }

    @Test
    fun `面を広げる幅と画面の左右の余白は同じ定数から取る`() {
        val header = stripComments(repositoryRoot().resolve(HEADER_PATH).readText())
        val bleed = BLEED_CALL.find(header)?.groupValues?.get(1)
        val padding = PADDING_START_END.find(header)

        assertTrue("bleedHorizontally の呼び出しが見つかりません", bleed != null)
        assertTrue("screenContentPadding の左右の余白が見つかりません", padding != null)
        assertEquals("左右で別の値を使っています", padding!!.groupValues[1], padding.groupValues[2])
        assertEquals("面を広げる幅と画面の余白が別の値です", bleed, padding.groupValues[1])
    }

    /**
     * 題名の行が丸ボタンより低いと、ボタンを置いた見出しだけが高くなる（ノートを開くたびに見出しの高さが変わる）。
     * 2つの値が別の数で書かれると、片方だけ変えたときにこの検査しか気づけない。
     */
    @Test
    fun `題名の行の高さと丸ボタンの大きさは同じ定数から取る`() {
        val header = stripComments(repositoryRoot().resolve(HEADER_PATH).readText())
        val pill = stripComments(repositoryRoot().resolve(PILL_PATH).readText())

        assertTrue("題名の行が丸ボタンの大きさを最低の高さに取っていません", TITLE_ROW_MIN_HEIGHT in header)
        assertTrue("丸ボタンの大きさが ICON_PILL_SIZE から取られていません", PILL_SIZE in pill)
    }

    private fun mainSources(): List<Pair<String, String>> {
        val root = repositoryRoot()
        return root.resolve(MAIN_SOURCE_DIR).walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "GradientHeader.kt" }
            .map { it.relativeTo(root).path to stripComments(it.readText()) }
            .toList()
    }

    private fun stripComments(source: String): String =
        LINE_COMMENT.replace(BLOCK_COMMENT.replace(source, " "), " ")

    private fun repositoryRoot(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(workingDirectory, workingDirectory.resolve("..")).firstOrNull { it.resolve("CLAUDE.md").isFile }
            ?: error("リポジトリルートが見つかりません（作業ディレクトリ: $workingDirectory）")
    }

    private companion object {
        const val MAIN_SOURCE_DIR = "app/src/main/java"
        const val HEADER_PATH = "app/src/main/java/com/example/newproject/ui/component/GradientHeader.kt"
        const val PILL_PATH = "app/src/main/java/com/example/newproject/ui/component/NoteComponents.kt"
        const val TITLE_ROW_MIN_HEIGHT = "heightIn(min = ICON_PILL_SIZE)"
        const val PILL_SIZE = ".size(ICON_PILL_SIZE)"
        val LINE_COMMENT = Regex("""//[^\n]*""")
        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")

        /** `GradientHeader(` の呼び出し。宣言 `fun GradientHeader(` は別ファイルなので数えない。 */
        val HEADER_CALL = Regex("""(?<![.\w])GradientHeader\(""")
        val BLEED_CALL = Regex("""bleedHorizontally\((\w+)\)""")
        val PADDING_START_END = Regex(
            """fun Modifier\.screenContentPadding\(\)[^=]*=\s*padding\(start = (\w+), end = (\w+)"""
        )
    }
}
