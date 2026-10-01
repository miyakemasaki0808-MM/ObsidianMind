package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外殻が本文を**どの形でも同じ位置で**組み立てていることを、構造で固定する。
 *
 * `rememberSaveable` は呼び出し位置で保存先を決め、その位置には前にある兄弟グループの数も入る。
 * 本文を形ごとに別の場所から呼ぶか、条件付きのレールやバーを本文より前に組み立てると、
 * Fold の開閉で NavHost 配下の保存値が形ごとに別々に戻る（→ features/margin_pane.md §11）。
 *
 * **振る舞いは `AppScaffoldStateRestorationTest`（androidTest）が作り直しを通して見る。**
 * ここは素のJVMで落とせる形だけを見る — 端末でしか走らない検査は、効いていることを毎回は確かめられない。
 */
class AppScaffoldContentSlotTest {

    private val source: String by lazy {
        val file = repositoryRoot().resolve(SOURCE_PATH)
        assertTrue("AppScaffold.kt がありません", file.isFile)
        LINE_COMMENT.replace(file.readText(), " ")
    }

    @Test
    fun `本文を呼ぶ場所は1か所だけ`() {
        assertEquals(
            "AppScaffold が本文（content）を複数の場所から呼んでいます",
            1,
            CONTENT_CALL.findAll(source).count()
        )
    }

    @Test
    fun `本文はレールとバーより先に組み立てる`() {
        val contentSlot = source.indexOf("layoutId(SLOT_CONTENT)")
        val railSlot = source.indexOf("layoutId(SLOT_RAIL)")
        val barSlot = source.indexOf("layoutId(SLOT_BAR)")
        assertTrue("本文・レール・バーの枠が見つかりません", contentSlot >= 0 && railSlot >= 0 && barSlot >= 0)
        assertTrue(
            "条件付きのレールかバーが本文より前に組み立てられています",
            contentSlot < railSlot && contentSlot < barSlot
        )
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(workingDirectory, workingDirectory.resolve("..")).firstOrNull { it.resolve("CLAUDE.md").isFile }
            ?: error("リポジトリルートが見つかりません（作業ディレクトリ: $workingDirectory）")
    }

    private companion object {
        const val SOURCE_PATH = "app/src/main/java/com/example/newproject/ui/AppScaffold.kt"
        val LINE_COMMENT = Regex("""//[^\n]*""")

        /** `content(` の呼び出し。引数名 `content = ` や型宣言 `content:` は数えない。 */
        val CONTENT_CALL = Regex("""(?<![.\w])content\(""")
    }
}
